package com.example.gamechat.chat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PresenceService {

    public record OnlineUser(UUID userId, String username, String status, Instant lastSeenAt) {
        public OnlineUser(UUID userId, String username) {
            this(userId, username, "ONLINE", null);
        }
    }

    public record ExpiredPresence(UUID roomId, UUID userId, String username) {
    }

    private static final DefaultRedisScript<Long> JOIN_SCRIPT = new DefaultRedisScript<>(
            """
            local hash = KEYS[1]
            local conns = KEYS[2]
            local hb = KEYS[3]
            local rooms = KEYS[4]
            local userId = ARGV[1]
            local payload = ARGV[2]
            local ttl = ARGV[3]
            local roomId = ARGV[4]
            local n = redis.call('INCR', conns)
            redis.call('HSET', hash, userId, payload)
            redis.call('SET', hb, '1', 'PX', ttl)
            redis.call('SADD', rooms, roomId)
            return n
            """,
            Long.class
    );

    private static final DefaultRedisScript<Long> LEAVE_SCRIPT = new DefaultRedisScript<>(
            """
            local hash = KEYS[1]
            local conns = KEYS[2]
            local hb = KEYS[3]
            local rooms = KEYS[4]
            local userId = ARGV[1]
            local roomId = ARGV[2]
            if redis.call('EXISTS', conns) == 0 then
              redis.call('HDEL', hash, userId)
              redis.call('DEL', hb)
              redis.call('SREM', rooms, roomId)
              return 0
            end
            local n = redis.call('DECR', conns)
            if n <= 0 then
              redis.call('DEL', conns)
              redis.call('HDEL', hash, userId)
              redis.call('DEL', hb)
              redis.call('SREM', rooms, roomId)
              return 0
            end
            return n
            """,
            Long.class
    );

    private static final DefaultRedisScript<Long> FORCE_SCRIPT = new DefaultRedisScript<>(
            """
            local hash = KEYS[1]
            local conns = KEYS[2]
            local hb = KEYS[3]
            local rooms = KEYS[4]
            local userId = ARGV[1]
            local roomId = ARGV[2]
            redis.call('DEL', conns)
            redis.call('HDEL', hash, userId)
            redis.call('DEL', hb)
            redis.call('SREM', rooms, roomId)
            return 1
            """,
            Long.class
    );

    private final StringRedisTemplate redis;
    private final long presenceTtlMs;

    public PresenceService(
            StringRedisTemplate redis,
            @Value("${app.chat.presence-ttl-ms}") long presenceTtlMs
    ) {
        this.redis = redis;
        this.presenceTtlMs = presenceTtlMs;
    }

    public boolean join(UUID roomId, UUID userId, String username) {
        String status = currentStatus(userId);
        if ("OFFLINE".equals(status) || status == null) {
            status = "ONLINE";
        }
        Long count = redis.execute(
                JOIN_SCRIPT,
                List.of(hashKey(roomId), connKey(roomId, userId), hbKey(roomId, userId), roomsKey(userId)),
                userId.toString(),
                payload(username, status),
                String.valueOf(presenceTtlMs),
                roomId.toString()
        );
        writeUser(userId, username, status, null);
        return count != null && count == 1L;
    }

    public boolean leave(UUID roomId, UUID userId) {
        Long remaining = redis.execute(
                LEAVE_SCRIPT,
                List.of(hashKey(roomId), connKey(roomId, userId), hbKey(roomId, userId), roomsKey(userId)),
                userId.toString(),
                roomId.toString()
        );
        return remaining == null || remaining <= 0L;
    }

    public void forceOffline(UUID roomId, UUID userId) {
        redis.execute(
                FORCE_SCRIPT,
                List.of(hashKey(roomId), connKey(roomId, userId), hbKey(roomId, userId), roomsKey(userId)),
                userId.toString(),
                roomId.toString()
        );
    }

    public void heartbeat(UUID userId, Set<UUID> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return;
        }
        for (UUID roomId : roomIds) {
            if (Boolean.TRUE.equals(redis.hasKey(connKey(roomId, userId)))) {
                redis.opsForValue().set(hbKey(roomId, userId), "1", java.time.Duration.ofMillis(presenceTtlMs));
            }
        }
    }

    public String setStatus(UUID userId, String username, String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        String normalized = status.trim().toUpperCase();
        if (!Set.of("ONLINE", "AWAY", "IN_GAME").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported presence status");
        }
        writeUser(userId, username, normalized, null);
        for (UUID roomId : roomsOf(userId)) {
            String existing = username(roomId, userId);
            redis.opsForHash().put(hashKey(roomId), userId.toString(), payload(existing == null ? username : existing, normalized));
        }
        return normalized;
    }

    public String currentStatus(UUID userId) {
        String raw = redis.opsForValue().get(userKey(userId));
        return parseStatus(raw, "ONLINE");
    }

    public Instant lastSeen(UUID userId) {
        return parseLastSeen(redis.opsForValue().get(userKey(userId)));
    }

    public void markOffline(UUID userId, String username) {
        writeUser(userId, username, "OFFLINE", Instant.now());
    }

    public Set<UUID> roomsOf(UUID userId) {
        Set<String> members = redis.opsForSet().members(roomsKey(userId));
        Set<UUID> rooms = new HashSet<>();
        if (members == null) {
            return rooms;
        }
        for (String member : members) {
            rooms.add(UUID.fromString(member));
        }
        return rooms;
    }

    public String username(UUID roomId, UUID userId) {
        Object value = redis.opsForHash().get(hashKey(roomId), userId.toString());
        return value == null ? null : parseUsername(String.valueOf(value));
    }

    public List<OnlineUser> onlineInRoom(UUID roomId) {
        Map<Object, Object> entries = redis.opsForHash().entries(hashKey(roomId));
        List<OnlineUser> online = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String userId = String.valueOf(entry.getKey());
            UUID id = UUID.fromString(userId);
            if (!Boolean.TRUE.equals(redis.hasKey(hbKey(roomId, id)))) {
                redis.opsForHash().delete(hashKey(roomId), userId);
                redis.delete(connKey(roomId, id));
                continue;
            }
            String raw = String.valueOf(entry.getValue());
            online.add(new OnlineUser(id, parseUsername(raw), parseStatus(raw, "ONLINE"), lastSeen(id)));
        }
        return online;
    }

    public List<ExpiredPresence> reapExpired() {
        List<ExpiredPresence> expired = new ArrayList<>();
        for (String hashKey : scan("presence:room:*")) {
            UUID roomId = UUID.fromString(hashKey.substring("presence:room:".length()));
            Map<Object, Object> entries = new LinkedHashMap<>(redis.opsForHash().entries(hashKey));
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                UUID userId = UUID.fromString(String.valueOf(entry.getKey()));
                if (Boolean.TRUE.equals(redis.hasKey(hbKey(roomId, userId)))) {
                    continue;
                }
                String username = parseUsername(String.valueOf(entry.getValue()));
                forceOffline(roomId, userId);
                expired.add(new ExpiredPresence(roomId, userId, username));
            }
        }
        return expired;
    }

    private void writeUser(UUID userId, String username, String status, Instant lastSeenAt) {
        String last = lastSeenAt == null ? "" : lastSeenAt.toString();
        String json = "{\"username\":\"" + escape(username) + "\",\"status\":\"" + status + "\",\"lastSeenAt\":\"" + last + "\"}";
        redis.opsForValue().set(userKey(userId), json);
    }

    private Set<String> scan(String pattern) {
        Set<String> scanned = redis.execute((RedisConnection connection) -> {
            Set<String> keys = new HashSet<>();
            ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return keys;
        });
        return scanned == null ? Set.of() : scanned;
    }

    private static String payload(String username, String status) {
        return "{\"username\":\"" + escape(username) + "\",\"status\":\"" + status + "\"}";
    }

    private static String parseUsername(String raw) {
        if (raw == null) {
            return "unknown";
        }
        if (!raw.startsWith("{")) {
            return raw;
        }
        int start = raw.indexOf("\"username\":\"");
        if (start < 0) {
            return raw;
        }
        int from = start + 12;
        int end = raw.indexOf('"', from);
        return end < 0 ? raw : raw.substring(from, end);
    }

    private static String parseStatus(String raw, String fallback) {
        if (raw == null || !raw.contains("\"status\"")) {
            return fallback;
        }
        int start = raw.indexOf("\"status\":\"");
        if (start < 0) {
            return fallback;
        }
        int from = start + 10;
        int end = raw.indexOf('"', from);
        return end < 0 ? fallback : raw.substring(from, end);
    }

    private static Instant parseLastSeen(String raw) {
        if (raw == null || !raw.contains("lastSeenAt")) {
            return null;
        }
        int start = raw.indexOf("\"lastSeenAt\":\"");
        if (start < 0) {
            return null;
        }
        int from = start + 14;
        int end = raw.indexOf('"', from);
        if (end < 0) {
            return null;
        }
        String value = raw.substring(from, end);
        if (value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String hashKey(UUID roomId) {
        return "presence:room:" + roomId;
    }

    static String connKey(UUID roomId, UUID userId) {
        return "presence:conn:" + roomId + ":" + userId;
    }

    static String hbKey(UUID roomId, UUID userId) {
        return "presence:hb:" + roomId + ":" + userId;
    }

    static String roomsKey(UUID userId) {
        return "presence:rooms:" + userId;
    }

    static String userKey(UUID userId) {
        return "presence:user:" + userId;
    }
}
