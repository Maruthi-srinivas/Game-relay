package com.example.gamechat.chat.websocket;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SessionRegistry {

    public record PresenceUser(UUID userId, String username) {
    }

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, UUID> userBySession = new ConcurrentHashMap<>();
    private final Map<String, String> usernameBySession = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSeenBySession = new ConcurrentHashMap<>();
    private final Map<UUID, Set<WebSocketSession>> sessionsByRoom = new ConcurrentHashMap<>();
    private final Map<String, Set<UUID>> roomsBySession = new ConcurrentHashMap<>();
    private final AtomicInteger authenticated = new AtomicInteger();

    public SessionRegistry() {
    }

    @Autowired
    public SessionRegistry(MeterRegistry meterRegistry) {
        Gauge.builder("chat.ws.connections", authenticated, AtomicInteger::get).register(meterRegistry);
    }

    public void register(WebSocketSession session, UUID userId, String username) {
        boolean already = userBySession.containsKey(session.getId());
        sessions.put(session.getId(), session);
        userBySession.put(session.getId(), userId);
        usernameBySession.put(session.getId(), username);
        lastSeenBySession.put(session.getId(), Instant.now());
        roomsBySession.putIfAbsent(session.getId(), new CopyOnWriteArraySet<>());
        if (!already) {
            authenticated.incrementAndGet();
        }
    }

    public void markPending(WebSocketSession session) {
        sessions.put(session.getId(), session);
        lastSeenBySession.put(session.getId(), Instant.now());
        session.getAttributes().put("openedAt", Instant.now());
    }

    public boolean isRegistered(WebSocketSession session) {
        return userBySession.containsKey(session.getId());
    }

    public boolean hasOtherSessions(UUID userId, WebSocketSession except) {
        if (userId == null) {
            return false;
        }
        for (Map.Entry<String, UUID> entry : userBySession.entrySet()) {
            if (userId.equals(entry.getValue()) && (except == null || !entry.getKey().equals(except.getId()))) {
                return true;
            }
        }
        return false;
    }

    public List<WebSocketSession> pendingAuthExpired(Instant cutoff) {
        List<WebSocketSession> expired = new ArrayList<>();
        for (WebSocketSession session : sessions.values()) {
            if (userBySession.containsKey(session.getId()) || !session.isOpen()) {
                continue;
            }
            Object opened = session.getAttributes().get("openedAt");
            if (opened instanceof Instant instant && instant.isBefore(cutoff)) {
                expired.add(session);
            }
        }
        return expired;
    }

    public void touch(WebSocketSession session) {
        lastSeenBySession.put(session.getId(), Instant.now());
    }

    public UUID requireUser(WebSocketSession session) {
        UUID userId = userBySession.get(session.getId());
        if (userId == null) {
            throw new IllegalStateException("Session is not registered");
        }
        return userId;
    }

    public String username(WebSocketSession session) {
        return usernameBySession.getOrDefault(session.getId(), "unknown");
    }

    public boolean isJoined(WebSocketSession session, UUID roomId) {
        Set<UUID> rooms = roomsBySession.get(session.getId());
        return rooms != null && rooms.contains(roomId);
    }

    /**
     * @return true if this is the first socket for that user in the room
     */
    public boolean joinRoom(WebSocketSession session, UUID roomId) {
        UUID userId = requireUser(session);
        boolean first = !isUserInRoom(userId, roomId);
        sessionsByRoom.computeIfAbsent(roomId, id -> new CopyOnWriteArraySet<>()).add(session);
        roomsBySession.computeIfAbsent(session.getId(), id -> new CopyOnWriteArraySet<>()).add(roomId);
        return first;
    }

    /**
     * @return true if the user has no remaining sockets in the room
     */
    public boolean leaveRoom(WebSocketSession session, UUID roomId) {
        UUID userId = userBySession.get(session.getId());
        removeSessionFromRoom(session, roomId);
        return userId != null && !isUserInRoom(userId, roomId);
    }

    /**
     * @return true if the user had a socket in the room (now offline there)
     */
    public boolean removeUserFromRoom(UUID userId, UUID roomId) {
        Set<WebSocketSession> roomSessions = sessionsByRoom.get(roomId);
        if (roomSessions == null) {
            return false;
        }
        boolean wasPresent = false;
        for (WebSocketSession session : Set.copyOf(roomSessions)) {
            if (userId.equals(userBySession.get(session.getId()))) {
                wasPresent = true;
                removeSessionFromRoom(session, roomId);
            }
        }
        return wasPresent;
    }

    public void removeSession(WebSocketSession session) {
        Set<UUID> rooms = roomsBySession.remove(session.getId());
        UUID userId = userBySession.remove(session.getId());
        usernameBySession.remove(session.getId());
        lastSeenBySession.remove(session.getId());
        sessions.remove(session.getId());
        if (userId != null) {
            authenticated.decrementAndGet();
        }
        if (rooms == null) {
            return;
        }
        for (UUID roomId : rooms) {
            Set<WebSocketSession> roomSessions = sessionsByRoom.get(roomId);
            if (roomSessions != null) {
                roomSessions.remove(session);
                if (roomSessions.isEmpty()) {
                    sessionsByRoom.remove(roomId);
                }
            }
        }
        // userId kept only so callers can compute offline rooms before removeSession;
        // this method is used from connection closed after the handler already broadcasts.
        if (userId == null) {
            return;
        }
    }

    /**
     * Rooms this session was in, before removal. Used to emit OFFLINE when the last socket drops.
     */
    public Set<UUID> roomsOf(WebSocketSession session) {
        Set<UUID> rooms = roomsBySession.get(session.getId());
        return rooms == null ? Set.of() : Set.copyOf(rooms);
    }

    public boolean isUserInRoom(UUID userId, UUID roomId) {
        Set<WebSocketSession> roomSessions = sessionsByRoom.get(roomId);
        if (roomSessions == null) {
            return false;
        }
        for (WebSocketSession session : roomSessions) {
            if (userId.equals(userBySession.get(session.getId()))) {
                return true;
            }
        }
        return false;
    }

    public Set<WebSocketSession> sessionsInRoom(UUID roomId) {
        Set<WebSocketSession> roomSessions = sessionsByRoom.get(roomId);
        return roomSessions == null ? Set.of() : Set.copyOf(roomSessions);
    }

    public Set<WebSocketSession> sessionsInRoomExcept(UUID roomId, WebSocketSession except) {
        Set<WebSocketSession> roomSessions = new CopyOnWriteArraySet<>(sessionsInRoom(roomId));
        roomSessions.removeIf(session -> session.getId().equals(except.getId()));
        return roomSessions;
    }

    public List<PresenceUser> onlineInRoom(UUID roomId) {
        Map<UUID, String> unique = new LinkedHashMap<>();
        for (WebSocketSession session : sessionsInRoom(roomId)) {
            UUID userId = userBySession.get(session.getId());
            if (userId != null) {
                unique.putIfAbsent(userId, usernameBySession.getOrDefault(session.getId(), userId.toString()));
            }
        }
        List<PresenceUser> users = new ArrayList<>();
        unique.forEach((userId, username) -> users.add(new PresenceUser(userId, username)));
        return users;
    }

    public List<WebSocketSession> idleSessions(Instant cutoff) {
        List<WebSocketSession> idle = new ArrayList<>();
        for (Map.Entry<String, Instant> entry : lastSeenBySession.entrySet()) {
            if (entry.getValue().isBefore(cutoff)) {
                WebSocketSession session = sessions.get(entry.getKey());
                if (session != null && session.isOpen()) {
                    idle.add(session);
                }
            }
        }
        return idle;
    }

    public static void send(WebSocketSession session, String payload) throws IOException {
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        }
    }

    private void removeSessionFromRoom(WebSocketSession session, UUID roomId) {
        Set<WebSocketSession> roomSessions = sessionsByRoom.get(roomId);
        if (roomSessions != null) {
            roomSessions.remove(session);
            if (roomSessions.isEmpty()) {
                sessionsByRoom.remove(roomId);
            }
        }
        Set<UUID> rooms = roomsBySession.get(session.getId());
        if (rooms != null) {
            rooms.remove(roomId);
        }
    }
}
