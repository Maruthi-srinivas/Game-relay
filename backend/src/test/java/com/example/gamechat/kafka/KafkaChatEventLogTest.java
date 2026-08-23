package com.example.gamechat.kafka;

import com.example.gamechat.chat.entity.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KafkaChatEventLogTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void publishesPersistedMessage() throws Exception {
        KafkaChatEventLog log = new KafkaChatEventLog(kafkaTemplate, new ObjectMapper());
        Message message = new Message();
        message.setId(UUID.randomUUID());
        message.setRoomId(UUID.randomUUID());
        message.setSenderId(UUID.randomUUID());
        message.setSequenceNumber(3);
        message.setCreatedAt(Instant.parse("2026-08-23T00:00:00Z"));

        log.messagePersisted(message);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("chat.message.persisted"), eq(message.getRoomId().toString()), payload.capture());
        assertThat(payload.getValue()).contains(message.getId().toString());
        assertThat(payload.getValue()).contains("\"sequenceNumber\":3");
    }
}
