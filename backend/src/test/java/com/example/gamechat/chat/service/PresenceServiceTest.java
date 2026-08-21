package com.example.gamechat.chat.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PresenceServiceTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate redis;

    private PresenceService presence;
    private UUID roomId;
    private UUID alice;
    private UUID bob;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379))
        );
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUp() {
        presence = new PresenceService(redis, 90_000);
        roomId = UUID.randomUUID();
        alice = UUID.randomUUID();
        bob = UUID.randomUUID();
    }

    @AfterEach
    void flush() {
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    @Test
    void joinReportsFirstConnectionThenSubsequent() {
        assertThat(presence.join(roomId, alice, "alice")).isTrue();
        assertThat(presence.join(roomId, alice, "alice")).isFalse();
        assertThat(presence.onlineInRoom(roomId))
                .extracting(PresenceService.OnlineUser::userId)
                .containsExactly(alice);
        assertThat(presence.username(roomId, alice)).isEqualTo("alice");
    }

    @Test
    void leaveReportsOfflineOnlyOnLastConnection() {
        presence.join(roomId, alice, "alice");
        presence.join(roomId, alice, "alice");

        assertThat(presence.leave(roomId, alice)).isFalse();
        assertThat(presence.onlineInRoom(roomId)).extracting(PresenceService.OnlineUser::userId).containsExactly(alice);
        assertThat(presence.leave(roomId, alice)).isTrue();
        assertThat(presence.onlineInRoom(roomId)).isEmpty();
    }

    @Test
    void snapshotIncludesUsersOnOtherLogicalNodes() {
        assertThat(presence.join(roomId, alice, "alice")).isTrue();
        assertThat(presence.join(roomId, bob, "bob")).isTrue();
        assertThat(presence.onlineInRoom(roomId))
                .extracting(PresenceService.OnlineUser::username)
                .containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void forceOfflineClearsPresenceSoNextJoinIsFirst() {
        presence.join(roomId, alice, "alice");
        presence.join(roomId, alice, "alice");
        presence.forceOffline(roomId, alice);
        assertThat(presence.onlineInRoom(roomId)).isEmpty();
        assertThat(presence.join(roomId, alice, "alice")).isTrue();
    }

    @Test
    void reapExpiredRemovesStaleHeartbeat() throws Exception {
        PresenceService shortTtl = new PresenceService(redis, 200);
        shortTtl.join(roomId, alice, "alice");
        Thread.sleep(400);
        List<PresenceService.ExpiredPresence> expired = shortTtl.reapExpired();
        assertThat(expired).hasSize(1);
        assertThat(expired.getFirst().userId()).isEqualTo(alice);
        assertThat(shortTtl.onlineInRoom(roomId)).isEmpty();
    }

    @Test
    void heartbeatExtendsTtl() throws Exception {
        PresenceService shortTtl = new PresenceService(redis, 400);
        shortTtl.join(roomId, alice, "alice");
        Thread.sleep(250);
        shortTtl.heartbeat(alice, Set.of(roomId));
        Thread.sleep(250);
        assertThat(shortTtl.onlineInRoom(roomId))
                .extracting(PresenceService.OnlineUser::userId)
                .containsExactly(alice);
        assertThat(shortTtl.reapExpired()).isEmpty();
    }
}
