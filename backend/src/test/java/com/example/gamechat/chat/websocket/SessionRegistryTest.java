package com.example.gamechat.chat.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionRegistryTest {

    private SessionRegistry registry;
    private UUID alice;
    private UUID bob;
    private UUID roomId;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
        roomId = UUID.randomUUID();
    }

    @Test
    void joinRoomReportsFirstSessionThenSubsequent() {
        WebSocketSession first = session("a1");
        WebSocketSession second = session("a2");
        registry.register(first, alice, "alice");
        registry.register(second, alice, "alice");

        assertThat(registry.joinRoom(first, roomId)).isTrue();
        assertThat(registry.joinRoom(second, roomId)).isFalse();
        assertThat(registry.onlineInRoom(roomId)).extracting(SessionRegistry.PresenceUser::userId)
                .containsExactly(alice);
    }

    @Test
    void leaveRoomReportsOfflineOnlyOnLastSocket() {
        WebSocketSession first = session("a1");
        WebSocketSession second = session("a2");
        registry.register(first, alice, "alice");
        registry.register(second, alice, "alice");
        registry.joinRoom(first, roomId);
        registry.joinRoom(second, roomId);

        assertThat(registry.leaveRoom(first, roomId)).isFalse();
        assertThat(registry.isUserInRoom(alice, roomId)).isTrue();
        assertThat(registry.leaveRoom(second, roomId)).isTrue();
        assertThat(registry.isUserInRoom(alice, roomId)).isFalse();
    }

    @Test
    void sessionsInRoomExceptOmitsCaller() {
        WebSocketSession aliceSession = session("a1");
        WebSocketSession bobSession = session("b1");
        registry.register(aliceSession, alice, "alice");
        registry.register(bobSession, bob, "bob");
        registry.joinRoom(aliceSession, roomId);
        registry.joinRoom(bobSession, roomId);

        assertThat(registry.sessionsInRoomExcept(roomId, aliceSession)).containsExactly(bobSession);
    }

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
