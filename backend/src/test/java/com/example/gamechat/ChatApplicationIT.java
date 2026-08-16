package com.example.gamechat;

import com.example.gamechat.auth.dto.AuthResponse;
import com.example.gamechat.chat.dto.MessageResponse;
import com.example.gamechat.common.dto.PageResponse;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChatApplicationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("jwt.secret", () -> "test-secret-must-be-at-least-32-chars-long");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

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
        assertThat(aliceRooms.getBody()).hasSize(1);
        assertThat(aliceRooms.getBody()[0].id()).isEqualTo(room.id());

        ResponseEntity<RoomResponse[]> carolRooms = exchange(
                carol.token(),
                HttpMethod.GET,
                "/api/rooms",
                null,
                RoomResponse[].class
        );
        assertThat(carolRooms.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(carolRooms.getBody()).isNotNull();
        assertThat(carolRooms.getBody()).isEmpty();

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
        assertThat(bobRooms.getBody()).hasSize(1);
        assertThat(bobRooms.getBody()[0].id()).isEqualTo(room.id());

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
            assertThat(aliceMessage.has("sequenceNumber")).isFalse();

            ResponseEntity<PageResponse<MessageResponse>> history = history(bob.token(), room.id());
            assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(history.getBody()).isNotNull();
            assertThat(history.getBody().content()).hasSize(1);
            assertThat(history.getBody().content().getFirst().content()).isEqualTo("Enemy approaching");
        } finally {
            aliceSession.close();
            bobSession.close();
            carolSession.close();
        }
    }

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
        URI uri = URI.create("ws://localhost:" + port + "/ws/chat?token=" + token);
        return new StandardWebSocketClient()
                .execute(handler, new WebSocketHttpHeaders(), uri)
                .get(5, TimeUnit.SECONDS);
    }

    private TextMessage joinPayload(UUID roomId, String requestId) throws Exception {
        return new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "JOIN_ROOM",
                "requestId", requestId,
                "roomId", roomId.toString()
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

    private static final class CollectingHandler extends TextWebSocketHandler {
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            messages.add(message.getPayload());
        }
    }
}
