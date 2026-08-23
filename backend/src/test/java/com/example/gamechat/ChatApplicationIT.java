package com.example.gamechat;

import com.example.gamechat.auth.dto.AuthResponse;
import com.example.gamechat.auth.security.JwtService;
import com.example.gamechat.chat.bus.ChatEvent;
import com.example.gamechat.chat.bus.ChatEventKind;
import com.example.gamechat.chat.bus.RedisConfig;
import com.example.gamechat.chat.dto.MessageResponse;
import com.example.gamechat.common.dto.PageResponse;
import com.example.gamechat.room.dto.InviteResponse;
import com.example.gamechat.room.dto.MemberResponse;
import com.example.gamechat.room.dto.RoomResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatApplicationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("jwt.secret", () -> "test-secret-must-be-at-least-32-chars-long");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void restAuthRoomsAndHistory() {
        AuthResponse alice = register("alice", "alice@example.com");
        AuthResponse bob = register("bob", "bob@example.com");
        AuthResponse carol = register("carol", "carol@example.com");

        ResponseEntity<AuthResponse> login = rest.postForEntity(
                "/api/auth/login",
                Map.of("username", "alice", "password", "password123"),
                AuthResponse.class
        );
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(login.getBody()).isNotNull();
        assertThat(login.getBody().token()).isNotBlank();
        assertThat(login.getBody().accessToken()).isNotBlank();

        ResponseEntity<AuthResponse> duplicate = rest.postForEntity(
                "/api/auth/register",
                Map.of("username", "alice", "email", "alice2@example.com", "password", "password123"),
                AuthResponse.class
        );
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        RoomResponse room = createRoom(alice.token(), "Arena");

        ResponseEntity<RoomResponse[]> aliceRooms = exchange(
                alice.token(),
                HttpMethod.GET,
                "/api/rooms",
                null,
                RoomResponse[].class
        );
        assertThat(aliceRooms.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aliceRooms.getBody()).isNotNull();
        assertThat(aliceRooms.getBody()).anyMatch(item -> item.id().equals(room.id()));
        assertThat(aliceRooms.getBody()).anyMatch(item -> "GLOBAL".equals(item.type()));

        ResponseEntity<RoomResponse[]> carolRooms = exchange(
                carol.token(),
                HttpMethod.GET,
                "/api/rooms",
                null,
                RoomResponse[].class
        );
        assertThat(carolRooms.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(carolRooms.getBody()).isNotNull();
        assertThat(carolRooms.getBody()).noneMatch(item -> item.id().equals(room.id()));
        assertThat(carolRooms.getBody()).anyMatch(item -> "GLOBAL".equals(item.type()));

        joinRoom(bob.token(), room.id());

        ResponseEntity<RoomResponse[]> bobRooms = exchange(
                bob.token(),
                HttpMethod.GET,
                "/api/rooms",
                null,
                RoomResponse[].class
        );
        assertThat(bobRooms.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bobRooms.getBody()).isNotNull();
        assertThat(bobRooms.getBody()).anyMatch(item -> item.id().equals(room.id()));

        ResponseEntity<RoomResponse> strangerGet = exchange(
                carol.token(),
                HttpMethod.GET,
                "/api/rooms/" + room.id(),
                null,
                RoomResponse.class
        );
        assertThat(strangerGet.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<MemberResponse[]> members = exchange(
                alice.token(),
                HttpMethod.GET,
                "/api/rooms/" + room.id() + "/members",
                null,
                MemberResponse[].class
        );
        assertThat(members.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(members.getBody()).hasSize(2);

        ResponseEntity<PageResponse<MessageResponse>> emptyHistory = history(alice.token(), room.id());
        assertThat(emptyHistory.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(emptyHistory.getBody()).isNotNull();
        assertThat(emptyHistory.getBody().content()).isEmpty();

        ResponseEntity<PageResponse<MessageResponse>> forbiddenHistory = history(carol.token(), room.id());
        assertThat(forbiddenHistory.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> unauthenticated = rest.getForEntity("/api/rooms/" + room.id(), String.class);
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void websocketBroadcastsPersistedMessages() throws Exception {
        AuthResponse alice = register("ws_alice", "ws.alice@example.com");
        AuthResponse bob = register("ws_bob", "ws.bob@example.com");
        AuthResponse carol = register("ws_carol", "ws.carol@example.com");
        RoomResponse room = createRoom(alice.token(), "WS Arena");
        joinRoom(bob.token(), room.id());

        CollectingHandler aliceHandler = new CollectingHandler();
        CollectingHandler bobHandler = new CollectingHandler();
        CollectingHandler carolHandler = new CollectingHandler();

        WebSocketSession aliceSession = connect(alice.token(), aliceHandler);
        WebSocketSession bobSession = connect(bob.token(), bobHandler);
        WebSocketSession carolSession = connect(carol.token(), carolHandler);

        try {
            assertThat(waitForType(aliceHandler, "CONNECTED").path("userId").asText())
                    .isEqualTo(alice.userId().toString());
            waitForType(bobHandler, "CONNECTED");
            waitForType(carolHandler, "CONNECTED");

            aliceSession.sendMessage(joinPayload(room.id(), "req-join-a"));
            bobSession.sendMessage(joinPayload(room.id(), "req-join-b"));
            waitForType(aliceHandler, "JOINED");
            waitForType(bobHandler, "JOINED");

            carolSession.sendMessage(joinPayload(room.id(), "req-join-c"));
            JsonNode carolError = waitForType(carolHandler, "ERROR");
            assertThat(carolError.path("code").asText()).isEqualTo("FORBIDDEN");

            aliceSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "SEND_MESSAGE",
                    "requestId", "req-1",
                    "roomId", room.id().toString(),
                    "content", "Enemy approaching"
            ))));

            JsonNode aliceMessage = waitForType(aliceHandler, "MESSAGE");
            JsonNode bobMessage = waitForType(bobHandler, "MESSAGE");
            assertThat(aliceMessage.path("content").asText()).isEqualTo("Enemy approaching");
            assertThat(aliceMessage.path("senderId").asText()).isEqualTo(alice.userId().toString());
            assertThat(aliceMessage.path("messageId").asText()).isEqualTo(bobMessage.path("messageId").asText());
            assertThat(aliceMessage.path("sequenceNumber").asLong()).isEqualTo(1);
            assertThat(bobMessage.path("sequenceNumber").asLong()).isEqualTo(1);

            ResponseEntity<PageResponse<MessageResponse>> history = history(bob.token(), room.id());
            assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(history.getBody()).isNotNull();
            assertThat(history.getBody().content()).hasSize(1);
            assertThat(history.getBody().content().getFirst().content()).isEqualTo("Enemy approaching");
            assertThat(history.getBody().content().getFirst().sequenceNumber()).isEqualTo(1);
        } finally {
            aliceSession.close();
            bobSession.close();
            carolSession.close();
        }
    }

    @Test
    void websocketSyncsMissedMessages() throws Exception {
        AuthResponse alice = register("sync_alice", "sync.alice@example.com");
        AuthResponse bob = register("sync_bob", "sync.bob@example.com");
        RoomResponse room = createRoom(alice.token(), "Sync Arena");
        joinRoom(bob.token(), room.id());

        CollectingHandler aliceHandler = new CollectingHandler();
        WebSocketSession aliceSession = connect(alice.token(), aliceHandler);
        try {
            waitForType(aliceHandler, "CONNECTED");
            aliceSession.sendMessage(joinPayload(room.id(), "req-join-a"));
            waitForType(aliceHandler, "JOINED");
            aliceSession.sendMessage(sendPayload(room.id(), "req-1", "missed while offline"));
            waitForType(aliceHandler, "ACK");
            assertThat(waitForType(aliceHandler, "MESSAGE").path("sequenceNumber").asLong()).isEqualTo(1);

            CollectingHandler bobHandler = new CollectingHandler();
            WebSocketSession bobSession = connect(bob.token(), bobHandler);
            try {
                waitForType(bobHandler, "CONNECTED");
                bobSession.sendMessage(joinPayload(room.id(), "req-join-b", 0));
                JsonNode sync = waitForType(bobHandler, "HISTORY_SYNC");
                assertThat(sync.path("truncated").asBoolean()).isFalse();
                assertThat(sync.path("messages")).hasSize(1);
                assertThat(sync.path("messages").get(0).path("content").asText()).isEqualTo("missed while offline");
                assertThat(sync.path("messages").get(0).path("sequenceNumber").asLong()).isEqualTo(1);
                waitForType(bobHandler, "JOINED");

                aliceSession.sendMessage(sendPayload(room.id(), "req-2", "live after sync"));
                JsonNode bobLive = waitForType(bobHandler, "MESSAGE");
                assertThat(bobLive.path("content").asText()).isEqualTo("live after sync");
                assertThat(bobLive.path("sequenceNumber").asLong()).isEqualTo(2);

                ResponseEntity<PageResponse<MessageResponse>> after = historyAfter(bob.token(), room.id(), 1);
                assertThat(after.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(after.getBody()).isNotNull();
                assertThat(after.getBody().content()).hasSize(1);
                assertThat(after.getBody().content().getFirst().content()).isEqualTo("live after sync");
                assertThat(after.getBody().content().getFirst().sequenceNumber()).isEqualTo(2);
            } finally {
                bobSession.close();
            }
        } finally {
            aliceSession.close();
        }
    }

    @Test
    void redisFanoutDeliversToLocalSockets() throws Exception {
        AuthResponse alice = register("fanout_alice", "fanout.alice@example.com");
        RoomResponse room = createRoom(alice.token(), "Fanout Arena");

        CollectingHandler aliceHandler = new CollectingHandler();
        WebSocketSession aliceSession = connect(alice.token(), aliceHandler);
        try {
            waitForType(aliceHandler, "CONNECTED");
            aliceSession.sendMessage(joinPayload(room.id(), "req-join-a"));
            waitForType(aliceHandler, "JOINED");

            var body = objectMapper.createObjectNode();
            body.put("type", "MESSAGE");
            body.put("messageId", UUID.randomUUID().toString());
            body.put("roomId", room.id().toString());
            body.put("senderId", alice.userId().toString());
            body.put("content", "from redis bus");
            body.put("timestamp", "2026-08-20T12:00:00Z");
            body.put("sequenceNumber", 99);
            ChatEvent event = new ChatEvent(ChatEventKind.MESSAGE, room.id(), null, body);
            redis.convertAndSend(RedisConfig.CHANNEL, objectMapper.writeValueAsString(event));

            JsonNode delivered = waitForType(aliceHandler, "MESSAGE");
            assertThat(delivered.path("content").asText()).isEqualTo("from redis bus");
            assertThat(delivered.path("sequenceNumber").asLong()).isEqualTo(99);
        } finally {
            aliceSession.close();
        }
    }

    @Test
    void refreshRotatesAccessToken() {
        ResponseEntity<AuthResponse> registered = rest.postForEntity(
                "/api/auth/register",
                Map.of("username", "refresh_alice", "email", "refresh.alice@example.com", "password", "password123"),
                AuthResponse.class
        );
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String setCookie = registered.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("refresh_token=");
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, setCookie.split(";", 2)[0]);
        ResponseEntity<AuthResponse> refreshed = rest.exchange(
                "/api/auth/refresh",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                AuthResponse.class
        );
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody()).isNotNull();
        assertThat(refreshed.getBody().accessToken()).isNotBlank();
        assertThat(refreshed.getBody().accessToken()).isNotEqualTo(registered.getBody().accessToken());
    }

    @Test
    void partyJoinRequiresInvite() {
        AuthResponse owner = register("party_owner", "party.owner@example.com");
        AuthResponse guest = register("party_guest", "party.guest@example.com");
        RoomResponse room = exchange(
                owner.token(),
                HttpMethod.POST,
                "/api/rooms",
                Map.of("name", "Night raid", "type", "PARTY"),
                RoomResponse.class
        ).getBody();
        assertThat(room).isNotNull();
        ResponseEntity<RoomResponse> denied = exchange(
                guest.token(),
                HttpMethod.POST,
                "/api/rooms/" + room.id() + "/join",
                null,
                RoomResponse.class
        );
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        InviteResponse invite = exchange(
                owner.token(),
                HttpMethod.POST,
                "/api/rooms/" + room.id() + "/invites",
                null,
                InviteResponse.class
        ).getBody();
        assertThat(invite).isNotNull();
        ResponseEntity<RoomResponse> joined = exchange(
                guest.token(),
                HttpMethod.POST,
                "/api/rooms/" + room.id() + "/join",
                Map.of("inviteCode", invite.code()),
                RoomResponse.class
        );
        assertThat(joined.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void privateRoomRejectsStrangerJoin() {
        AuthResponse alice = register("dm_alice", "dm.alice@example.com");
        AuthResponse bob = register("dm_bob", "dm.bob@example.com");
        AuthResponse carol = register("dm_carol", "dm.carol@example.com");
        RoomResponse dm = exchange(
                alice.token(),
                HttpMethod.POST,
                "/api/rooms/private",
                Map.of("userId", bob.userId().toString()),
                RoomResponse.class
        ).getBody();
        assertThat(dm).isNotNull();
        assertThat(dm.type()).isEqualTo("PRIVATE");
        ResponseEntity<RoomResponse> denied = exchange(
                carol.token(),
                HttpMethod.POST,
                "/api/rooms/" + dm.id() + "/join",
                null,
                RoomResponse.class
        );
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        RoomResponse again = exchange(
                bob.token(),
                HttpMethod.POST,
                "/api/rooms/private",
                Map.of("userId", alice.userId().toString()),
                RoomResponse.class
        ).getBody();
        assertThat(again).isNotNull();
        assertThat(again.id()).isEqualTo(dm.id());
    }

    @Test
    void logoutDenylistsAccessToken() {
        ResponseEntity<AuthResponse> registered = rest.postForEntity(
                "/api/auth/register",
                Map.of("username", "logout_alice", "email", "logout.alice@example.com", "password", "password123"),
                AuthResponse.class
        );
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registered.getBody()).isNotNull();
        String setCookie = registered.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("refresh_token=");
        HttpHeaders headers = bearer(registered.getBody().accessToken());
        headers.add(HttpHeaders.COOKIE, setCookie.split(";", 2)[0]);
        ResponseEntity<Void> logout = rest.exchange(
                "/api/auth/logout",
                HttpMethod.POST,
                new HttpEntity<>(headers),
                Void.class
        );
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<String> rooms = exchange(
                registered.getBody().accessToken(),
                HttpMethod.GET,
                "/api/rooms",
                null,
                String.class
        );
        assertThat(rooms.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredAccessTokenIsRejected() throws Exception {
        JwtService shortLived = new JwtService("test-secret-must-be-at-least-32-chars-long", 1);
        String token = shortLived.createToken(UUID.randomUUID(), "ghost");
        Thread.sleep(25);
        ResponseEntity<String> response = exchange(token, HttpMethod.GET, "/api/rooms", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void setPresenceFansOutToRoom() throws Exception {
        AuthResponse alice = register("presence_alice", "presence.alice@example.com");
        AuthResponse bob = register("presence_bob", "presence.bob@example.com");
        RoomResponse room = createRoom(alice.token(), "Presence Arena");
        joinRoom(bob.token(), room.id());

        CollectingHandler aliceHandler = new CollectingHandler();
        CollectingHandler bobHandler = new CollectingHandler();
        WebSocketSession aliceSession = connect(alice.token(), aliceHandler);
        WebSocketSession bobSession = connect(bob.token(), bobHandler);
        try {
            waitForType(aliceHandler, "CONNECTED");
            waitForType(bobHandler, "CONNECTED");
            aliceSession.sendMessage(joinPayload(room.id(), "req-join-a"));
            bobSession.sendMessage(joinPayload(room.id(), "req-join-b"));
            waitForType(aliceHandler, "JOINED");
            waitForType(bobHandler, "JOINED");
            aliceSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "SET_PRESENCE",
                    "status", "IN_GAME"
            ))));
            JsonNode presence = waitUntil(bobHandler, node ->
                    "PRESENCE".equals(node.path("type").asText())
                            && alice.userId().toString().equals(node.path("userId").asText())
                            && "IN_GAME".equals(node.path("status").asText()));
            assertThat(presence.path("status").asText()).isEqualTo("IN_GAME");
        } finally {
            aliceSession.close();
            bobSession.close();
        }
    }

    @Test
    void twoSocketsStayOnlineUntilLastCloses() throws Exception {
        AuthResponse alice = register("tabs_alice", "tabs.alice@example.com");
        RoomResponse room = createRoom(alice.token(), "Tabs Arena");
        CollectingHandler firstHandler = new CollectingHandler();
        CollectingHandler secondHandler = new CollectingHandler();
        WebSocketSession first = connect(alice.token(), firstHandler);
        WebSocketSession second = connect(alice.token(), secondHandler);
        try {
            waitForType(firstHandler, "CONNECTED");
            waitForType(secondHandler, "CONNECTED");
            first.sendMessage(joinPayload(room.id(), "req-join-1"));
            second.sendMessage(joinPayload(room.id(), "req-join-2"));
            waitForType(firstHandler, "JOINED");
            waitForType(secondHandler, "JOINED");
            first.close();
            Thread.sleep(200);
            JsonNode still = presence(alice.token(), alice.userId());
            assertThat(still.path("status").asText()).isNotEqualTo("OFFLINE");
            second.close();
            Thread.sleep(200);
            JsonNode offline = presence(alice.token(), alice.userId());
            assertThat(offline.path("status").asText()).isEqualTo("OFFLINE");
            assertThat(offline.path("lastSeenAt").asText()).isNotBlank();
        } finally {
            if (first.isOpen()) {
                first.close();
            }
            if (second.isOpen()) {
                second.close();
            }
        }
    }

    @Test
    void muteBlocksSend() throws Exception {
        AuthResponse alice = register("mute_alice", "mute.alice@example.com");
        AuthResponse bob = register("mute_bob", "mute.bob@example.com");
        RoomResponse room = createRoom(alice.token(), "Mute Arena");
        joinRoom(bob.token(), room.id());
        ResponseEntity<Void> muted = exchange(
                alice.token(),
                HttpMethod.POST,
                "/api/rooms/" + room.id() + "/members/" + bob.userId() + "/mute",
                null,
                Void.class
        );
        assertThat(muted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        CollectingHandler bobHandler = new CollectingHandler();
        WebSocketSession bobSession = connect(bob.token(), bobHandler);
        try {
            waitForType(bobHandler, "CONNECTED");
            bobSession.sendMessage(joinPayload(room.id(), "req-join-b"));
            waitForType(bobHandler, "JOINED");
            bobSession.sendMessage(sendPayload(room.id(), "req-muted", "should fail"));
            JsonNode error = waitForType(bobHandler, "ERROR");
            assertThat(error.path("code").asText()).isEqualTo("FORBIDDEN");
        } finally {
            bobSession.close();
        }
    }

    @Test
    void kickDropsSocketFromRoom() throws Exception {
        AuthResponse alice = register("kick_alice", "kick.alice@example.com");
        AuthResponse bob = register("kick_bob", "kick.bob@example.com");
        RoomResponse room = createRoom(alice.token(), "Kick Arena");
        joinRoom(bob.token(), room.id());
        CollectingHandler bobHandler = new CollectingHandler();
        WebSocketSession bobSession = connect(bob.token(), bobHandler);
        try {
            waitForType(bobHandler, "CONNECTED");
            bobSession.sendMessage(joinPayload(room.id(), "req-join-b"));
            waitForType(bobHandler, "JOINED");
            ResponseEntity<Void> kicked = exchange(
                    alice.token(),
                    HttpMethod.POST,
                    "/api/rooms/" + room.id() + "/members/" + bob.userId() + "/kick",
                    null,
                    Void.class
            );
            assertThat(kicked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            Thread.sleep(150);
            bobSession.sendMessage(sendPayload(room.id(), "req-after-kick", "nope"));
            JsonNode error = waitForType(bobHandler, "ERROR");
            assertThat(error.path("code").asText()).isEqualTo("FORBIDDEN");
        } finally {
            bobSession.close();
        }
    }

    @Test
    void softDeleteHidesMessageFromHistory() throws Exception {
        AuthResponse alice = register("del_alice", "del.alice@example.com");
        RoomResponse room = createRoom(alice.token(), "Delete Arena");
        CollectingHandler aliceHandler = new CollectingHandler();
        WebSocketSession aliceSession = connect(alice.token(), aliceHandler);
        try {
            waitForType(aliceHandler, "CONNECTED");
            aliceSession.sendMessage(joinPayload(room.id(), "req-join-a"));
            waitForType(aliceHandler, "JOINED");
            aliceSession.sendMessage(sendPayload(room.id(), "req-1", "scratch this"));
            JsonNode message = waitForType(aliceHandler, "MESSAGE");
            String messageId = message.path("messageId").asText();
            aliceSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "DELETE_MESSAGE",
                    "roomId", room.id().toString(),
                    "messageId", messageId
            ))));
            waitForType(aliceHandler, "MESSAGE_DELETED");
            ResponseEntity<PageResponse<MessageResponse>> history = history(alice.token(), room.id());
            assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(history.getBody()).isNotNull();
            assertThat(history.getBody().content()).isEmpty();
        } finally {
            aliceSession.close();
        }
    }

    @Test
    void banBlocksRejoin() {
        AuthResponse alice = register("ban_alice", "ban.alice@example.com");
        AuthResponse bob = register("ban_bob", "ban.bob@example.com");
        RoomResponse room = createRoom(alice.token(), "Ban Arena");
        joinRoom(bob.token(), room.id());
        ResponseEntity<Void> banned = exchange(
                alice.token(),
                HttpMethod.POST,
                "/api/rooms/" + room.id() + "/members/" + bob.userId() + "/ban",
                null,
                Void.class
        );
        assertThat(banned.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<String> rejoin = exchange(
                bob.token(),
                HttpMethod.POST,
                "/api/rooms/" + room.id() + "/join",
                null,
                String.class
        );
        assertThat(rejoin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void promoteAllowsMute() {
        AuthResponse alice = register("promo_alice", "promo.alice@example.com");
        AuthResponse bob = register("promo_bob", "promo.bob@example.com");
        AuthResponse carol = register("promo_carol", "promo.carol@example.com");
        RoomResponse room = createRoom(alice.token(), "Promo Arena");
        joinRoom(bob.token(), room.id());
        joinRoom(carol.token(), room.id());
        ResponseEntity<Void> promoted = exchange(
                alice.token(),
                HttpMethod.POST,
                "/api/rooms/" + room.id() + "/members/" + bob.userId() + "/promote",
                null,
                Void.class
        );
        assertThat(promoted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<Void> muted = exchange(
                bob.token(),
                HttpMethod.POST,
                "/api/rooms/" + room.id() + "/members/" + carol.userId() + "/mute",
                null,
                Void.class
        );
        assertThat(muted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void duplicateRequestIdPersistsOnce() throws Exception {
        AuthResponse alice = register("dup_alice", "dup.alice@example.com");
        RoomResponse room = createRoom(alice.token(), "Idempotent Arena");
        CollectingHandler handler = new CollectingHandler();
        WebSocketSession session = connect(alice.token(), handler);
        try {
            waitForType(handler, "CONNECTED");
            session.sendMessage(joinPayload(room.id(), "req-join-a"));
            waitForType(handler, "JOINED");
            session.sendMessage(sendPayload(room.id(), "req-dup", "once only"));
            waitForType(handler, "MESSAGE");
            session.sendMessage(sendPayload(room.id(), "req-dup", "once only"));
            waitForType(handler, "ACK");
            ResponseEntity<PageResponse<MessageResponse>> history = history(alice.token(), room.id());
            assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(history.getBody()).isNotNull();
            assertThat(history.getBody().content()).hasSize(1);
            assertThat(history.getBody().content().getFirst().content()).isEqualTo("once only");
        } finally {
            session.close();
        }
    }

    @Test
    void searchFindsPersistedMessage() throws Exception {
        AuthResponse alice = register("srch_alice", "srch.alice@example.com");
        RoomResponse room = createRoom(alice.token(), "Search Arena");
        CollectingHandler handler = new CollectingHandler();
        WebSocketSession session = connect(alice.token(), handler);
        try {
            waitForType(handler, "CONNECTED");
            session.sendMessage(joinPayload(room.id(), "req-join-a"));
            waitForType(handler, "JOINED");
            session.sendMessage(sendPayload(room.id(), "req-1", "uniquephrasexyz visible"));
            waitForType(handler, "MESSAGE");
            ResponseEntity<PageResponse<MessageResponse>> found = rest.exchange(
                    "/api/rooms/" + room.id() + "/messages/search?q=uniquephrasexyz&size=20",
                    HttpMethod.GET,
                    new HttpEntity<>(bearer(alice.token())),
                    new ParameterizedTypeReference<PageResponse<MessageResponse>>() {
                    }
            );
            assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(found.getBody()).isNotNull();
            assertThat(found.getBody().content()).extracting(MessageResponse::content)
                    .anyMatch(content -> content != null && content.contains("uniquephrasexyz"));
        } finally {
            session.close();
        }
    }

    @Test
    void duplicateReactionStaysUnique() throws Exception {
        AuthResponse alice = register("react_alice", "react.alice@example.com");
        RoomResponse room = createRoom(alice.token(), "Reaction Arena");
        CollectingHandler handler = new CollectingHandler();
        WebSocketSession session = connect(alice.token(), handler);
        try {
            waitForType(handler, "CONNECTED");
            session.sendMessage(joinPayload(room.id(), "req-join-a"));
            waitForType(handler, "JOINED");
            session.sendMessage(sendPayload(room.id(), "req-1", "react to me"));
            JsonNode message = waitForType(handler, "MESSAGE");
            String messageId = message.path("messageId").asText();
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "ADD_REACTION",
                    "requestId", "req-re-1",
                    "roomId", room.id().toString(),
                    "messageId", messageId,
                    "emoji", "👍"
            ))));
            waitForType(handler, "REACTION");
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "ADD_REACTION",
                    "requestId", "req-re-2",
                    "roomId", room.id().toString(),
                    "messageId", messageId,
                    "emoji", "👍"
            ))));
            waitForType(handler, "REACTION");
            ResponseEntity<PageResponse<MessageResponse>> history = history(alice.token(), room.id());
            assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(history.getBody()).isNotNull();
            assertThat(history.getBody().content()).isNotEmpty();
            assertThat(history.getBody().content().getFirst().reactions()).hasSize(1);
            assertThat(history.getBody().content().getFirst().reactions().getFirst().count()).isEqualTo(1);
        } finally {
            session.close();
        }
    }

    @Test
    void mutedMemberCannotAttach() {
        AuthResponse alice = register("att_alice", "att.alice@example.com");
        AuthResponse bob = register("att_bob", "att.bob@example.com");
        RoomResponse room = createRoom(alice.token(), "Attach Arena");
        joinRoom(bob.token(), room.id());
        ResponseEntity<Void> muted = exchange(
                alice.token(),
                HttpMethod.POST,
                "/api/rooms/" + room.id() + "/members/" + bob.userId() + "/mute",
                null,
                Void.class
        );
        assertThat(muted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bob.token());
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ByteArrayResource file = new ByteArrayResource(PNG_BYTES) {
            @Override
            public String getFilename() {
                return "x.png";
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.IMAGE_PNG);
        body.add("file", new HttpEntity<>(file, fileHeaders));
        ResponseEntity<String> uploaded = rest.exchange(
                "/api/rooms/" + room.id() + "/attachments",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                String.class
        );
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private static final byte[] PNG_BYTES = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02,
            0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53, (byte) 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49,
            0x44, 0x41, 0x54, 0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xCF, (byte) 0xC0, 0x00,
            0x00, 0x00, 0x03, 0x00, 0x01, 0x18, (byte) 0xDD, (byte) 0x8D, (byte) 0xB4, 0x00, 0x00,
            0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    private AuthResponse register(String username, String email) {
        ResponseEntity<AuthResponse> response = rest.postForEntity(
                "/api/auth/register",
                Map.of("username", username, "email", email, "password", "password123"),
                AuthResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private RoomResponse createRoom(String token, String name) {
        ResponseEntity<RoomResponse> response = exchange(
                token,
                HttpMethod.POST,
                "/api/rooms",
                Map.of("name", name),
                RoomResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void joinRoom(String token, UUID roomId) {
        ResponseEntity<RoomResponse> response = exchange(
                token,
                HttpMethod.POST,
                "/api/rooms/" + roomId + "/join",
                null,
                RoomResponse.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<PageResponse<MessageResponse>> history(String token, UUID roomId) {
        HttpHeaders headers = bearer(token);
        return rest.exchange(
                "/api/rooms/" + roomId + "/messages?page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<PageResponse<MessageResponse>>() {
                }
        );
    }

    private ResponseEntity<PageResponse<MessageResponse>> historyAfter(String token, UUID roomId, long afterSequence) {
        HttpHeaders headers = bearer(token);
        return rest.exchange(
                "/api/rooms/" + roomId + "/messages?afterSequence=" + afterSequence + "&size=20",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<PageResponse<MessageResponse>>() {
                }
        );
    }

    private <T> ResponseEntity<T> exchange(
            String token,
            HttpMethod method,
            String path,
            Object body,
            Class<T> type
    ) {
        HttpHeaders headers = bearer(token);
        HttpEntity<?> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return rest.exchange(path, method, entity, type);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private WebSocketSession connect(String token, CollectingHandler handler) throws Exception {
        URI uri = URI.create("ws://localhost:" + port + "/ws/chat");
        WebSocketSession session = new StandardWebSocketClient()
                .execute(handler, new WebSocketHttpHeaders(), uri)
                .get(5, TimeUnit.SECONDS);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "AUTH",
                "token", token
        ))));
        return session;
    }

    private TextMessage joinPayload(UUID roomId, String requestId) throws Exception {
        return joinPayload(roomId, requestId, null);
    }

    private TextMessage joinPayload(UUID roomId, String requestId, Integer afterSequence) throws Exception {
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("type", "JOIN_ROOM");
        body.put("requestId", requestId);
        body.put("roomId", roomId.toString());
        if (afterSequence != null) {
            body.put("afterSequence", afterSequence);
        }
        return new TextMessage(objectMapper.writeValueAsString(body));
    }

    private TextMessage sendPayload(UUID roomId, String requestId, String content) throws Exception {
        return new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "SEND_MESSAGE",
                "requestId", requestId,
                "roomId", roomId.toString(),
                "content", content
        )));
    }

    private JsonNode waitForType(CollectingHandler handler, String type) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            String raw = handler.messages.poll(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
            if (raw == null) {
                break;
            }
            JsonNode node = objectMapper.readTree(raw);
            if (type.equals(node.path("type").asText())) {
                return node;
            }
        }
        throw new AssertionError("Did not receive WebSocket event type " + type + " from " + handler.messages);
    }

    private JsonNode waitUntil(CollectingHandler handler, Predicate<JsonNode> match) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            long remaining = deadline - System.nanoTime();
            String raw = handler.messages.poll(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
            if (raw == null) {
                break;
            }
            JsonNode node = objectMapper.readTree(raw);
            if (match.test(node)) {
                return node;
            }
        }
        throw new AssertionError("Did not receive matching WebSocket event from " + handler.messages);
    }

    private JsonNode presence(String token, UUID userId) throws Exception {
        ResponseEntity<String> response = exchange(
                token,
                HttpMethod.GET,
                "/api/users/" + userId + "/presence",
                null,
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    private static final class CollectingHandler extends TextWebSocketHandler {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }
    }
}
