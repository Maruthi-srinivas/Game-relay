package com.example.gamechat.chat.bus;

import com.example.gamechat.chat.websocket.LocalFrameSender;
import com.example.gamechat.chat.websocket.SessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatEventListenerTest {

    @Mock
    private LocalFrameSender localFrameSender;
    @Mock
    private SessionRegistry sessionRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ChatEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChatEventListener(objectMapper, localFrameSender, sessionRegistry);
    }

    @Test
    void deliversMessageBodyToLocalRoom() throws Exception {
        UUID roomId = UUID.randomUUID();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("type", "MESSAGE");
        body.put("content", "from redis bus");
        ChatEvent event = new ChatEvent(ChatEventKind.MESSAGE, roomId, "skip-me", body);

        listener.onMessage(redisMessage(event), null);

        verify(localFrameSender).sendToRoom(eq(roomId), contains("from redis bus"), eq("skip-me"));
        verifyNoInteractions(sessionRegistry);
    }

    @Test
    void dropUserRemovesLocalSockets() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("userId", userId.toString());
        ChatEvent event = new ChatEvent(ChatEventKind.DROP_USER, roomId, null, body);

        listener.onMessage(redisMessage(event), null);

        verify(sessionRegistry).removeUserFromRoom(userId, roomId);
        verifyNoInteractions(localFrameSender);
    }

    @Test
    void ignoresMalformedPayload() {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("not-json".getBytes());

        listener.onMessage(message, null);

        verifyNoInteractions(localFrameSender, sessionRegistry);
    }

    private Message redisMessage(ChatEvent event) throws Exception {
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(objectMapper.writeValueAsBytes(event));
        return message;
    }
}
