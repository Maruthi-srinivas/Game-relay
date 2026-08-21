package com.example.gamechat.chat.service;

import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.chat.repository.MessageRepository;
import com.example.gamechat.common.exception.ApiException;
import com.example.gamechat.room.entity.Room;
import com.example.gamechat.room.repository.RoomRepository;
import com.example.gamechat.room.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private RoomRepository roomRepository;
    @Mock
    private RoomService roomService;

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(messageRepository, roomRepository, roomService, 2000, 100);
    }

    @Test
    void rejectsBlankContent() {
        assertThatThrownBy(() -> messageService.validateContent("   "))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("BAD_REQUEST");
    }

    @Test
    void rejectsOversizedContent() {
        assertThatThrownBy(() -> messageService.validateContent("x".repeat(2001)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("BAD_REQUEST");
    }

    @Test
    void trimsValidContent() {
        assertThat(messageService.validateContent("  hello  ")).isEqualTo("hello");
    }

    @Test
    void saveIncrementsRoomSequence() {
        UUID roomId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        Room room = new Room();
        room.setId(roomId);
        room.setLastSequence(3);
        when(roomRepository.findByIdForUpdate(roomId)).thenReturn(Optional.of(room));
        when(messageRepository.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message saved = messageService.save(roomId, senderId, "  hi  ");

        assertThat(saved.getSequenceNumber()).isEqualTo(4);
        assertThat(saved.getContent()).isEqualTo("hi");
        assertThat(room.getLastSequence()).isEqualTo(4);
    }
}
