package com.example.gamechat.chat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PresenceService {

    public record OnlineUser(UUID userId, String username) {
    }

    public record ExpiredPresence(UUID roomId, UUID userId, String username) {
    }

    private static final DefaultRedisScript<Long> JOIN_SCRIPT = new DefaultRedisScript<>(
            """
            local hash = KEYS[1]
            local conns = KEYS[2]
            local hb = KEYS[3]
            local userId = ARGV[1]
            local username = ARGV[2]
            local ttl = ARGV[3]
            local n = redis.call('INCR', conns)
            redis.call('HSET', hash, userId, username)
            redis.call('SET', hb, '1', 'PX', ttl)
            return n
            """,
            Long.class
    );

    private static final DefaultRedisScript<Long> LEAVE_SCRIPT = new DefaultRedisScript<>(
            """
            local hash = KEYS[1]
            local conns = KEYS[2]
            local hb = KEYS[3]
            local userId = ARGV[1]
            if redis.call('EXISTS', conns) == 0 then
              redis.call('HDEL', hash, userId)
              redis.call('DEL', hb)
              return 0
            end
            local n = redis.call('DECR', conns)
            if n <= 0 then
              redis.call('DEL', conns)
              redis.call('HDEL', hash, userId)
              redis.call('DEL', hb)
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
            local userId = ARGV[1]
            redis.call('DEL', conns)
            redis.call('HDEL', hash, userId)
            redis.call('DEL', hb)
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

    /**
     * @return true if this is the user's first connection in the room cluster-wide
     */
    public boolean join(UUID roomId, UUID userId, String username) {
        Long count = redis.execute(
                JOIN_SCRIPT,
                List.of(hashKey(roomId), connKey(roomId, userId), hbKey(roomId, userId)),
                userId.toString(),
                username,
                String.valueOf(presenceTtlMs)
        );
        return count != null && count == 1L;
    }

    /**
     * @return true if the user has no remaining connections in the room cluster-wide
     */
    public boolean leave(UUID roomId, UUID userId) {
        Long remaining = redis.execute(
                LEAVE_SCRIPT,
                List.of(hashKey(roomId), connKey(roomId, userId), hbKey(roomId, userId)),
                userId.toString()
        );
        return remaining == null || remaining <= 0L;
    }

    public void forceOffline(UUID roomId, UUID userId) {
        redis.execute(
                FORCE_SCRIPT,
                List.of(hashKey(roomId), connKey(roomId, userId), hbKey(roomId, userId)),
                userId.toString()
        );
    }

    public void heartbeat(UUID userId, Set<UUID> roomIds) {
        if (roomIds == null || roomIds.isEmpty()) {
            return;
        }
        for (UUID roomId : roomIds) {
            String conns = connKey(roomId, userId);
            Boolean exists = redis.hasKey(conns);
            if (Boolean.TRUE.equals(exists)) {
                redis.opsForValue().set(hbKey(roomId, userId), "1", java.time.Duration.ofMillis(presenceTtlMs));
            }
        }
    }

    public String username(UUID roomId, UUID userId) {
        Object value = redis.opsForHash().get(hashKey(roomId), userId.toString());
        return value == null ? null : String.valueOf(value);
    }

    public List<OnlineUser> onlineInRoom(UUID roomId) {
        Map<Object, Object> entries = redis.opsForHash().entries(hashKey(roomId));
        List<OnlineUser> online = new ArrayList<>();
        for (Map.Entry<Object, Object> entry : entries.entrySet()) {
            String userId = String.valueOf(entry.getKey());
            if (!Boolean.TRUE.equals(redis.hasKey(hbKey(roomId, UUID.fromString(userId))))) {
                redis.opsForHash().delete(hashKey(roomId), userId);
                redis.delete(connKey(roomId, UUID.fromString(userId)));
                continue;
            }
            online.add(new OnlineUser(UUID.fromString(userId), String.valueOf(entry.getValue())));
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
                String username = String.valueOf(entry.getValue());
                forceOffline(roomId, userId);
                expired.add(new ExpiredPresence(roomId, userId, username));
            }
        }
        return expired;
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

    static String hashKey(UUID roomId) {
        return "presence:room:" + roomId;
    }

    static String connKey(UUID roomId, UUID userId) {
        return "presence:conn:" + roomId + ":" + userId;
    }

    static String hbKey(UUID roomId, UUID userId) {
        return "presence:hb:" + roomId + ":" + userId;
    }
}
