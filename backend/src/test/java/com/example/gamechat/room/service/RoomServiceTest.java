package com.example.gamechat.room.service;

import com.example.gamechat.auth.entity.User;
import com.example.gamechat.auth.repository.UserRepository;
import com.example.gamechat.common.exception.ApiException;
import com.example.gamechat.room.dto.CreateRoomRequest;
import com.example.gamechat.room.dto.RoomResponse;
import com.example.gamechat.room.entity.Room;
import com.example.gamechat.room.entity.RoomMember;
import com.example.gamechat.room.repository.RoomMemberRepository;
import com.example.gamechat.room.repository.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomServiceTest {

    @Mock
    private RoomRepository roomRepository;
    @Mock
    private RoomMemberRepository roomMemberRepository;
    @Mock
    private UserRepository userRepository;

    private RoomService roomService;
    private UUID ownerId;
    private User owner;

    @BeforeEach
    void setUp() throws Exception {
        roomService = new RoomService(roomRepository, roomMemberRepository, userRepository);
        ownerId = UUID.randomUUID();
        owner = new User();
        owner.setId(ownerId);
        owner.setUsername("owner");
    }

    @Test
    void createAddsOwnerMembership() throws Exception {
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(roomRepository.save(any(Room.class))).thenAnswer(invocation -> {
            Room room = invocation.getArgument(0);
            persist(room);
            return room;
        });
        when(roomMemberRepository.save(any(RoomMember.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RoomResponse response = roomService.create(ownerId, new CreateRoomRequest("Lobby", null, null));

        assertThat(response.name()).isEqualTo("Lobby");
        assertThat(response.type()).isEqualTo("GAME_ROOM");
        assertThat(response.ownerId()).isEqualTo(ownerId);
        verify(roomMemberRepository).save(any(RoomMember.class));
    }

    @Test
    void joinIsIdempotentForExistingMember() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId);
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.existsByIdRoomIdAndIdUserId(roomId, ownerId)).thenReturn(true);

        RoomResponse response = roomService.join(roomId, ownerId);

        assertThat(response.id()).isEqualTo(roomId);
        verify(roomMemberRepository, never()).save(any());
    }

    @Test
    void joinRejectsWhenRoomIsFull() {
        UUID roomId = UUID.randomUUID();
        Room room = room(roomId);
        room.setMaxMembers(2);
        UUID joinerId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room));
        when(roomMemberRepository.existsByIdRoomIdAndIdUserId(roomId, joinerId)).thenReturn(false);
        when(roomMemberRepository.countByIdRoomId(roomId)).thenReturn(2L);

        assertThatThrownBy(() -> roomService.join(roomId, joinerId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CONFLICT");
    }

    @Test
    void requireMemberRejectsStrangers() {
        UUID roomId = UUID.randomUUID();
        when(roomRepository.findById(roomId)).thenReturn(Optional.of(room(roomId)));
        when(roomMemberRepository.existsByIdRoomIdAndIdUserId(roomId, ownerId)).thenReturn(false);

        assertThatThrownBy(() -> roomService.requireMember(roomId, ownerId))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("FORBIDDEN");
    }

    @Test
    void rejectsUnknownRoomType() {
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        assertThatThrownBy(() -> roomService.create(ownerId, new CreateRoomRequest("x", "VOICE", 10)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("BAD_REQUEST");
    }

    private Room room(UUID roomId) {
        Room room = new Room();
        room.setId(roomId);
        room.setName("Arena");
        room.setType("GAME_ROOM");
        room.setOwner(owner);
        room.setMaxMembers(50);
        try {
            persist(room);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return room;
    }

    private static void persist(Room room) throws Exception {
        Method method = Room.class.getDeclaredMethod("prePersist");
        method.setAccessible(true);
        method.invoke(room);
    }
}
