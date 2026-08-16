package com.example.gamechat.chat.service;

import com.example.gamechat.chat.repository.MessageRepository;
import com.example.gamechat.common.exception.ApiException;
import com.example.gamechat.room.service.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private RoomService roomService;

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(messageRepository, roomService, 2000);
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
}
