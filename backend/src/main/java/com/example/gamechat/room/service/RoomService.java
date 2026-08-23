package com.example.gamechat.room.service;

import com.example.gamechat.auth.entity.User;
import com.example.gamechat.auth.repository.UserRepository;
import com.example.gamechat.chat.bus.ChatEvent;
import com.example.gamechat.chat.bus.ChatEventKind;
import com.example.gamechat.chat.bus.ChatEventPublisher;
import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.chat.repository.MessageRepository;
import com.example.gamechat.common.exception.ApiException;
import com.example.gamechat.kafka.ChatEventLog;
import com.example.gamechat.room.dto.CreatePrivateRequest;
import com.example.gamechat.room.dto.CreateRoomRequest;
import com.example.gamechat.room.dto.InviteResponse;
import com.example.gamechat.room.dto.JoinRoomRequest;
import com.example.gamechat.room.dto.MemberResponse;
import com.example.gamechat.room.dto.ReportRequest;
import com.example.gamechat.room.dto.ReportResponse;
import com.example.gamechat.room.dto.RoomResponse;
import com.example.gamechat.room.entity.Report;
import com.example.gamechat.room.entity.Room;
import com.example.gamechat.room.entity.RoomInvite;
import com.example.gamechat.room.entity.RoomMember;
import com.example.gamechat.room.entity.RoomMemberId;
import com.example.gamechat.room.repository.ReportRepository;
import com.example.gamechat.room.repository.RoomInviteRepository;
import com.example.gamechat.room.repository.RoomMemberRepository;
import com.example.gamechat.room.repository.RoomRepository;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class RoomService {

    public static final UUID GLOBAL_ROOM_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

    private static final Set<String> ALLOWED_TYPES = Set.of(
            "GLOBAL", "GAME_ROOM", "TEAM", "PARTY", "PRIVATE"
    );
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final UserRepository userRepository;
    private final RoomInviteRepository roomInviteRepository;
    private final ReportRepository reportRepository;
    private final MessageRepository messageRepository;
    private final ChatEventPublisher publisher;
    private final ChatEventLog chatEventLog;
    private final ObjectMapper objectMapper;

    public RoomService(
            RoomRepository roomRepository,
            RoomMemberRepository roomMemberRepository,
            UserRepository userRepository,
            RoomInviteRepository roomInviteRepository,
            ReportRepository reportRepository,
            MessageRepository messageRepository,
            ChatEventPublisher publisher,
            ChatEventLog chatEventLog,
            ObjectMapper objectMapper
    ) {
        this.roomRepository = roomRepository;
        this.roomMemberRepository = roomMemberRepository;
        this.userRepository = userRepository;
        this.roomInviteRepository = roomInviteRepository;
        this.reportRepository = reportRepository;
        this.messageRepository = messageRepository;
        this.publisher = publisher;
        this.chatEventLog = chatEventLog;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RoomResponse create(UUID ownerId, CreateRoomRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        String type = request.type() == null || request.type().isBlank()
                ? "GAME_ROOM"
                : request.type().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(type)) {
            throw ApiException.badRequest("Unsupported room type");
        }
        if ("GLOBAL".equals(type) || "PRIVATE".equals(type)) {
            throw ApiException.badRequest("Use the dedicated endpoint for " + type + " rooms");
        }
        Room room = new Room();
        room.setName(request.name().trim());
        room.setType(type);
        room.setOwner(owner);
        int defaultMax = "PARTY".equals(type) ? 8 : 50;
        room.setMaxMembers(request.maxMembers() == null ? defaultMax : request.maxMembers());
        roomRepository.save(room);
        addMember(room, owner, "OWNER");
        return toResponse(room);
    }

    @Transactional
    public RoomResponse createPrivate(UUID callerId, CreatePrivateRequest request) {
        if (callerId.equals(request.userId())) {
            throw ApiException.badRequest("Cannot start a private chat with yourself");
        }
        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        User peer = userRepository.findById(request.userId())
                .orElseThrow(() -> ApiException.notFound("User not found"));
        String key = directKey(callerId, peer.getId());
        return roomRepository.findByDirectKey(key)
                .map(this::toResponse)
                .orElseGet(() -> {
                    Room room = new Room();
                    room.setName(peer.getUsername());
                    room.setType("PRIVATE");
                    room.setOwner(caller);
                    room.setMaxMembers(2);
                    room.setDirectKey(key);
                    roomRepository.save(room);
                    addMember(room, caller, "OWNER");
                    addMember(room, peer, "MEMBER");
                    publishMembership(ChatEventKind.USER_JOINED, room.getId(), peer.getId(), peer.getUsername());
                    return toResponse(room);
                });
    }

    @Transactional
    public void autoJoinGlobal(UUID userId) {
        Room global = roomRepository.findById(GLOBAL_ROOM_ID)
                .or(() -> roomRepository.findFirstByType("GLOBAL"))
                .orElse(null);
        if (global == null) {
            return;
        }
        if (roomMemberRepository.existsByIdRoomIdAndIdUserId(global.getId(), userId)) {
            return;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        addMember(global, user, "MEMBER");
        publishMembership(ChatEventKind.USER_JOINED, global.getId(), userId, user.getUsername());
    }

    @Transactional(readOnly = true)
    public RoomResponse get(UUID roomId, UUID userId) {
        Room room = requireMember(roomId, userId);
        return toResponse(room);
    }

    @Transactional
    public RoomResponse join(UUID roomId, UUID userId, JoinRoomRequest request) {
        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> ApiException.notFound("Room not found"));
        if (roomMemberRepository.existsByIdRoomIdAndIdUserId(roomId, userId)) {
            return toResponse(room);
        }
        enforceJoinRules(room, userId, request == null ? null : request.inviteCode());
        long memberCount = roomMemberRepository.countByIdRoomId(roomId);
        if (memberCount >= room.getMaxMembers()) {
            throw ApiException.conflict("Room is full");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        addMember(room, user, "MEMBER");
        publishMembership(ChatEventKind.USER_JOINED, roomId, userId, user.getUsername());
        return toResponse(room);
    }

    @Transactional
    public RoomResponse joinByCode(UUID userId, String code) {
        RoomInvite invite = requireInvite(code);
        return join(invite.getRoom().getId(), userId, new JoinRoomRequest(code));
    }

    @Transactional
    public void leave(UUID roomId, UUID userId) {
        Room room = requireExistingRoom(roomId);
        RoomMemberId memberId = new RoomMemberId(roomId, userId);
        RoomMember member = roomMemberRepository.findById(memberId)
                .orElseThrow(() -> ApiException.forbidden("Not a member of this room"));
        if ("GLOBAL".equals(room.getType())) {
            throw ApiException.forbidden("Cannot leave the global lobby");
        }
        String username = member.getUser().getUsername();
        roomMemberRepository.delete(member);
        if ("OWNER".equals(member.getRole())) {
            transferOrClose(room, userId);
        }
        publishMembership(ChatEventKind.USER_LEFT, roomId, userId, username);
    }

    @Transactional
    public InviteResponse createInvite(UUID roomId, UUID userId) {
        Room room = requireMember(roomId, userId);
        requireModerator(roomId, userId);
        if ("PRIVATE".equals(room.getType()) || "GLOBAL".equals(room.getType())) {
            throw ApiException.badRequest("This room type does not use invites");
        }
        User creator = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        RoomInvite invite = new RoomInvite();
        invite.setRoom(room);
        invite.setCreatedBy(creator);
        invite.setCode(randomCode());
        invite.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        roomInviteRepository.save(invite);
        return new InviteResponse(invite.getCode(), roomId, invite.getExpiresAt());
    }

    @Transactional
    public void kick(UUID roomId, UUID actorId, UUID targetId) {
        requireModerator(roomId, actorId);
        if (actorId.equals(targetId)) {
            throw ApiException.badRequest("Cannot kick yourself");
        }
        RoomMember target = roomMemberRepository.findByIdRoomIdAndIdUserId(roomId, targetId)
                .orElseThrow(() -> ApiException.notFound("Member not found"));
        if ("OWNER".equals(target.getRole())) {
            throw ApiException.forbidden("Cannot kick the owner");
        }
        String username = target.getUser().getUsername();
        roomMemberRepository.delete(target);
        publishMembership(ChatEventKind.USER_LEFT, roomId, targetId, username);
        chatEventLog.moderation("KICK", roomId, actorId, null);
    }

    @Transactional
    public void setMuted(UUID roomId, UUID actorId, UUID targetId, boolean muted) {
        requireModerator(roomId, actorId);
        RoomMember target = roomMemberRepository.findByIdRoomIdAndIdUserId(roomId, targetId)
                .orElseThrow(() -> ApiException.notFound("Member not found"));
        if ("OWNER".equals(target.getRole()) && muted) {
            throw ApiException.forbidden("Cannot mute the owner");
        }
        target.setMuted(muted);
        chatEventLog.moderation(muted ? "MUTE" : "UNMUTE", roomId, actorId, null);
    }

    @Transactional
    public ReportResponse report(UUID roomId, UUID reporterId, ReportRequest request) {
        Room room = requireMember(roomId, reporterId);
        User reporter = userRepository.findById(reporterId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        Report report = new Report();
        report.setRoom(room);
        report.setReporter(reporter);
        report.setReason(request.reason().trim());
        if (request.targetUserId() != null) {
            report.setTargetUser(userRepository.findById(request.targetUserId())
                    .orElseThrow(() -> ApiException.notFound("User not found")));
        }
        if (request.messageId() != null) {
            Message message = messageRepository.findById(request.messageId())
                    .orElseThrow(() -> ApiException.notFound("Message not found"));
            if (!message.getRoomId().equals(roomId)) {
                throw ApiException.badRequest("Message is not in this room");
            }
            report.setMessage(message);
        }
        reportRepository.save(report);
        chatEventLog.moderation("REPORT", roomId, reporterId, request.messageId());
        return new ReportResponse(report.getId());
    }

    @Transactional(readOnly = true)
    public List<RoomResponse> listForUser(UUID userId) {
        return roomMemberRepository.findWithRoomsByUserId(userId).stream()
                .map(member -> toResponse(member.getRoom()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> listMembers(UUID roomId, UUID userId) {
        requireMember(roomId, userId);
        return roomMemberRepository.findWithUsersByRoomId(roomId).stream()
                .map(member -> new MemberResponse(
                        member.getUser().getId(),
                        member.getUser().getUsername(),
                        member.getRole(),
                        member.getJoinedAt(),
                        member.isMuted()
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

    @Transactional(readOnly = true)
    public boolean isMuted(UUID roomId, UUID userId) {
        return roomMemberRepository.findByIdRoomIdAndIdUserId(roomId, userId)
                .map(RoomMember::isMuted)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isModerator(UUID roomId, UUID userId) {
        return roomMemberRepository.findByIdRoomIdAndIdUserId(roomId, userId)
                .map(member -> "OWNER".equals(member.getRole()) || "MODERATOR".equals(member.getRole()))
                .orElse(false);
    }

    private void enforceJoinRules(Room room, UUID userId, String inviteCode) {
        String type = room.getType();
        if ("PRIVATE".equals(type)) {
            throw ApiException.forbidden("Private rooms cannot be joined by id");
        }
        if ("PARTY".equals(type) || "TEAM".equals(type)) {
            requireInviteForRoom(room.getId(), inviteCode);
        }
        if ("GLOBAL".equals(type)) {
            return;
        }
    }

    private void requireInviteForRoom(UUID roomId, String code) {
        RoomInvite invite = requireInvite(code);
        if (!invite.getRoom().getId().equals(roomId)) {
            throw ApiException.forbidden("Invite does not match this room");
        }
    }

    private RoomInvite requireInvite(String code) {
        if (code == null || code.isBlank()) {
            throw ApiException.forbidden("Invite code required");
        }
        RoomInvite invite = roomInviteRepository.findByCodeIgnoreCase(code.trim())
                .orElseThrow(() -> ApiException.forbidden("Invalid invite"));
        if (invite.getExpiresAt() != null && invite.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.forbidden("Invite expired");
        }
        return invite;
    }

    private void requireModerator(UUID roomId, UUID userId) {
        requireMember(roomId, userId);
        if (!isModerator(roomId, userId)) {
            throw ApiException.forbidden("Moderator access required");
        }
    }

    private void transferOrClose(Room room, UUID leavingOwnerId) {
        List<RoomMember> remaining = roomMemberRepository.findByIdRoomIdOrderByJoinedAtAsc(room.getId());
        remaining = remaining.stream()
                .filter(member -> !member.getId().getUserId().equals(leavingOwnerId))
                .toList();
        if (remaining.isEmpty()) {
            return;
        }
        RoomMember next = remaining.getFirst();
        next.setRole("OWNER");
        room.setOwner(next.getUser());
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
        member.setMuted(false);
        roomMemberRepository.save(member);
    }

    private void publishMembership(ChatEventKind kind, UUID roomId, UUID userId, String username) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", kind.name());
        body.put("roomId", roomId.toString());
        body.put("userId", userId.toString());
        body.put("username", username);
        publisher.publishAfterCommit(new ChatEvent(kind, roomId, null, body));
        if (kind == ChatEventKind.USER_JOINED) {
            chatEventLog.userJoined(roomId, userId);
        } else if (kind == ChatEventKind.USER_LEFT) {
            chatEventLog.userLeft(roomId, userId);
        }
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

    private static String directKey(UUID a, UUID b) {
        String left = a.toString();
        String right = b.toString();
        return left.compareTo(right) < 0 ? left + ":" + right : right + ":" + left;
    }

    private static String randomCode() {
        StringBuilder builder = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            builder.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return builder.toString();
    }
}
