package com.example.gamechat.room.service;

import com.example.gamechat.auth.entity.User;
import com.example.gamechat.auth.repository.UserRepository;
import com.example.gamechat.common.exception.ApiException;
import com.example.gamechat.room.dto.CreateRoomRequest;
import com.example.gamechat.room.dto.MemberResponse;
import com.example.gamechat.room.dto.RoomResponse;
import com.example.gamechat.room.entity.Room;
import com.example.gamechat.room.entity.RoomMember;
import com.example.gamechat.room.entity.RoomMemberId;
import com.example.gamechat.room.repository.RoomMemberRepository;
import com.example.gamechat.room.repository.RoomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class RoomService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "GLOBAL", "GAME_ROOM", "TEAM", "PARTY", "PRIVATE"
    );

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final UserRepository userRepository;

    public RoomService(
            RoomRepository roomRepository,
            RoomMemberRepository roomMemberRepository,
            UserRepository userRepository
    ) {
        this.roomRepository = roomRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public RoomResponse create(UUID ownerId, CreateRoomRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        String type = request.type() == null || request.type().isBlank()
                ? "GAME_ROOM"
                : request.type().trim().toUpperCase();
        if (!ALLOWED_TYPES.contains(type)) {
            throw ApiException.badRequest("Unsupported room type");
        }
        Room room = new Room();
        room.setName(request.name().trim());
        room.setType(type);
        room.setOwner(owner);
        room.setMaxMembers(request.maxMembers() == null ? 50 : request.maxMembers());
        roomRepository.save(room);
        addMember(room, owner, "OWNER");
        return toResponse(room);
    }

    @Transactional(readOnly = true)
    public RoomResponse get(UUID roomId, UUID userId) {
        Room room = requireMember(roomId, userId);
        return toResponse(room);
    }

    @Transactional
    public RoomResponse join(UUID roomId, UUID userId) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> ApiException.notFound("Room not found"));
        if (roomMemberRepository.existsByIdRoomIdAndIdUserId(roomId, userId)) {
            return toResponse(room);
        }
        long memberCount = roomMemberRepository.countByIdRoomId(roomId);
        if (memberCount >= room.getMaxMembers()) {
            throw ApiException.conflict("Room is full");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        addMember(room, user, "MEMBER");
        return toResponse(room);
    }

    @Transactional
    public void leave(UUID roomId, UUID userId) {
        requireExistingRoom(roomId);
        RoomMemberId memberId = new RoomMemberId(roomId, userId);
        if (!roomMemberRepository.existsById(memberId)) {
            throw ApiException.forbidden("Not a member of this room");
        }
        roomMemberRepository.deleteById(memberId);
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(UUID roomId, UUID userId) {
        requireMember(roomId, userId);
        return roomMemberRepository.findWithUsersByRoomId(roomId).stream()
                .map(member -> new MemberResponse(
                        member.getUser().getId(),
                        member.getUser().getUsername(),
                        member.getRole(),
                        member.getJoinedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public Room requireMember(UUID roomId, UUID userId) {
        Room room = requireExistingRoom(roomId);
        if (!roomMemberRepository.existsByIdRoomIdAndIdUserId(roomId, userId)) {
            throw ApiException.forbidden("Not a member of this room");
        }
        return room;
    }

    @Transactional(readOnly = true)
    public boolean isMember(UUID roomId, UUID userId) {
        return roomMemberRepository.existsByIdRoomIdAndIdUserId(roomId, userId);
    }

    private Room requireExistingRoom(UUID roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> ApiException.notFound("Room not found"));
    }

    private void addMember(Room room, User user, String role) {
        RoomMember member = new RoomMember();
        member.setId(new RoomMemberId(room.getId(), user.getId()));
        member.setRoom(room);
        member.setUser(user);
        member.setRole(role);
        roomMemberRepository.save(member);
    }

    private RoomResponse toResponse(Room room) {
        return new RoomResponse(
                room.getId(),
                room.getName(),
                room.getType(),
                room.getOwner().getId(),
                room.getMaxMembers(),
                room.getCreatedAt(),
                room.getUpdatedAt()
        );
    }
}
