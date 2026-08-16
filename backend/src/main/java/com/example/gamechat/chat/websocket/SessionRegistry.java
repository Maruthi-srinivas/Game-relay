package com.example.gamechat.chat.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class SessionRegistry {

    private final Map<String, UUID> userBySession = new ConcurrentHashMap<>();
    private final Map<UUID, Set<WebSocketSession>> sessionsByRoom = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> roomsBySession = new ConcurrentHashMap<>();

    public void register(WebSocketSession session, UUID userId) {
        userBySession.put(session.getId(), userId);
        roomsBySession.putIfAbsent(session.getId(), new CopyOnWriteArraySet<>());
    }

    public UUID requireUser(WebSocketSession session) {
        UUID userId = userBySession.get(session.getId());
        if (userId == null) {
            throw new IllegalStateException("Session is not registered");
        }
        return userId;
    }

    public void joinRoom(WebSocketSession session, UUID roomId) {
        sessionsByRoom.computeIfAbsent(roomId, id -> new CopyOnWriteArraySet<>()).add(session);
        roomsBySession.computeIfAbsent(session.getId(), id -> new CopyOnWriteArraySet<>()).add(roomId);
    }

    public void leaveRoom(WebSocketSession session, UUID roomId) {
        Set<WebSocketSession> sessions = sessionsByRoom.get(roomId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByRoom.remove(roomId);
            }
        }
        Set<UUID> rooms = roomsBySession.get(session.getId());
        if (rooms != null) {
            rooms.remove(roomId);
        }
    }

    public void removeUserFromRoom(UUID userId, UUID roomId) {
        Set<WebSocketSession> sessions = sessionsByRoom.get(roomId);
        if (sessions == null) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (userId.equals(userBySession.get(session.getId()))) {
                leaveRoom(session, roomId);
            }
        }
    }

    public void removeSession(WebSocketSession session) {
        Set<UUID> rooms = roomsBySession.remove(session.getId());
        userBySession.remove(session.getId());
        if (rooms == null) {
            return;
        }
        for (UUID roomId : rooms) {
            Set<WebSocketSession> sessions = sessionsByRoom.get(roomId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    sessionsByRoom.remove(roomId);
                }
            }
        }
    }

    public Set<WebSocketSession> sessionsInRoom(UUID roomId) {
        Set<WebSocketSession> sessions = sessionsByRoom.get(roomId);
        return sessions == null ? Set.of() : Set.copyOf(sessions);
    }

    public static void send(WebSocketSession session, String payload) throws IOException {
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        }
    }
}
