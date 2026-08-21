package com.example.gamechat.chat.websocket;

import com.example.gamechat.auth.security.UserPrincipal;
import com.example.gamechat.chat.bus.ChatEvent;
import com.example.gamechat.chat.bus.ChatEventKind;
import com.example.gamechat.chat.bus.ChatEventPublisher;
import com.example.gamechat.chat.dto.MessageResponse;
import com.example.gamechat.chat.dto.SyncBatch;
import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.chat.service.ChatService;
import com.example.gamechat.chat.service.PresenceService;
import com.example.gamechat.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final SessionRegistry sessionRegistry;
    private final ChatService chatService;
    private final PresenceService presenceService;
    private final ChatEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(
            SessionRegistry sessionRegistry,
            ChatService chatService,
            PresenceService presenceService,
            ChatEventPublisher publisher,
            ObjectMapper objectMapper
    ) {
        this.sessionRegistry = sessionRegistry;
        this.chatService = chatService;
        this.presenceService = presenceService;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UserPrincipal principal = (UserPrincipal) session.getAttributes().get("user");
        sessionRegistry.register(session, principal.userId(), principal.username());
        ObjectNode connected = objectMapper.createObjectNode();
        connected.put("type", "CONNECTED");
        connected.put("userId", principal.userId().toString());
        connected.put("username", principal.username());
        SessionRegistry.send(session, objectMapper.writeValueAsString(connected));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        sessionRegistry.touch(session);
        heartbeatPresence(session);
        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (Exception ex) {
            sendError(session, null, "BAD_REQUEST", "Malformed JSON");
            return;
        }
        if (root == null || !root.hasNonNull("type")) {
            sendError(session, text(root, "requestId"), "BAD_REQUEST", "Missing event type");
            return;
        }
        String type = root.get("type").asText();
        String requestId = text(root, "requestId");
        try {
            switch (type) {
                case "JOIN_ROOM" -> handleJoin(session, root, requestId);
                case "LEAVE_ROOM" -> handleLeave(session, root, requestId);
                case "SEND_MESSAGE" -> handleSend(session, root, requestId);
                case "PING" -> handlePing(session, requestId);
                case "TYPING" -> handleTyping(session, root, requestId);
                default -> sendError(session, requestId, "UNSUPPORTED_TYPE", "Unsupported event type: " + type);
            }
        } catch (ApiException ex) {
            sendError(session, requestId, ex.getCode(), ex.getMessage());
        } catch (IllegalArgumentException ex) {
            sendError(session, requestId, "BAD_REQUEST", ex.getMessage());
        } catch (Exception ex) {
            log.error("WebSocket handler failure", ex);
            sendError(session, requestId, "INTERNAL_ERROR", "Unexpected server error");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        emitOfflineForSession(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket transport error session={}", session.getId(), exception);
        emitOfflineForSession(session);
    }

    public void dropUserFromRoom(UUID userId, UUID roomId) {
        String username = presenceService.username(roomId, userId);
        if (username == null) {
            username = sessionRegistry.onlineInRoom(roomId).stream()
                    .filter(user -> user.userId().equals(userId))
                    .map(SessionRegistry.PresenceUser::username)
                    .findFirst()
                    .orElse("unknown");
        }
        presenceService.forceOffline(roomId, userId);
        ObjectNode drop = objectMapper.createObjectNode();
        drop.put("userId", userId.toString());
        publisher.publish(new ChatEvent(ChatEventKind.DROP_USER, roomId, null, drop));
        publishPresence(roomId, userId, username, "OFFLINE");
    }

    private void handleJoin(WebSocketSession session, JsonNode root, String requestId) throws IOException {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        chatService.requireMember(roomId, userId);
        boolean first = subscribeLocalAndPresence(session, roomId, userId);
        sendHistorySync(session, userId, roomId, afterSequence(root), requestId);
        sendJoined(session, roomId, requestId);
        sendPresenceSnapshot(session, roomId);
        if (first) {
            publishPresence(roomId, userId, sessionRegistry.username(session), "ONLINE");
        }
    }

    private void handleLeave(WebSocketSession session, JsonNode root, String requestId) throws IOException {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        String username = sessionRegistry.username(session);
        boolean wasJoined = sessionRegistry.isJoined(session, roomId);
        sessionRegistry.leaveRoom(session, roomId);
        ObjectNode ack = objectMapper.createObjectNode();
        ack.put("type", "LEFT");
        ack.put("roomId", roomId.toString());
        putRequestId(ack, requestId);
        SessionRegistry.send(session, objectMapper.writeValueAsString(ack));
        if (wasJoined && presenceService.leave(roomId, userId)) {
            publishPresence(roomId, userId, username, "OFFLINE");
        }
    }

    private void handleSend(WebSocketSession session, JsonNode root, String requestId) throws IOException {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        if (!sessionRegistry.isJoined(session, roomId)) {
            chatService.requireMember(roomId, userId);
            if (subscribeLocalAndPresence(session, roomId, userId)) {
                publishPresence(roomId, userId, sessionRegistry.username(session), "ONLINE");
            }
        }
        String content = text(root, "content");
        Message saved = chatService.sendMessage(userId, roomId, content);
        sendAck(session, saved, requestId);
        publisher.publishAfterCommit(new ChatEvent(ChatEventKind.MESSAGE, roomId, null, messageNode(saved, requestId)));
    }

    private void handlePing(WebSocketSession session, String requestId) throws IOException {
        ObjectNode pong = objectMapper.createObjectNode();
        pong.put("type", "PONG");
        putRequestId(pong, requestId);
        SessionRegistry.send(session, objectMapper.writeValueAsString(pong));
    }

    private void handleTyping(WebSocketSession session, JsonNode root, String requestId) {
        UUID roomId = requireRoomId(root);
        if (!sessionRegistry.isJoined(session, roomId)) {
            return;
        }
        boolean isTyping = root.has("isTyping") && root.get("isTyping").asBoolean(false);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "TYPING");
        payload.put("roomId", roomId.toString());
        payload.put("userId", sessionRegistry.requireUser(session).toString());
        payload.put("username", sessionRegistry.username(session));
        payload.put("isTyping", isTyping);
        putRequestId(payload, requestId);
        publisher.publish(new ChatEvent(ChatEventKind.TYPING, roomId, session.getId(), payload));
    }

    private boolean subscribeLocalAndPresence(WebSocketSession session, UUID roomId, UUID userId) {
        if (sessionRegistry.isJoined(session, roomId)) {
            return false;
        }
        sessionRegistry.joinRoom(session, roomId);
        return presenceService.join(roomId, userId, sessionRegistry.username(session));
    }

    private void sendHistorySync(
            WebSocketSession session,
            UUID userId,
            UUID roomId,
            long afterSequence,
            String requestId
    ) throws IOException {
        SyncBatch batch = chatService.sync(userId, roomId, afterSequence);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "HISTORY_SYNC");
        payload.put("roomId", roomId.toString());
        payload.put("truncated", batch.truncated());
        ArrayNode messages = payload.putArray("messages");
        long from = 0;
        long to = 0;
        for (int i = 0; i < batch.messages().size(); i++) {
            MessageResponse message = batch.messages().get(i);
            if (i == 0) {
                from = message.sequenceNumber();
            }
            to = message.sequenceNumber();
            messages.add(messageNode(message, null));
        }
        if (!batch.messages().isEmpty()) {
            payload.put("fromSequence", from);
            payload.put("toSequence", to);
        }
        putRequestId(payload, requestId);
        SessionRegistry.send(session, objectMapper.writeValueAsString(payload));
    }

    private void sendJoined(WebSocketSession session, UUID roomId, String requestId) throws IOException {
        ObjectNode ack = objectMapper.createObjectNode();
        ack.put("type", "JOINED");
        ack.put("roomId", roomId.toString());
        putRequestId(ack, requestId);
        SessionRegistry.send(session, objectMapper.writeValueAsString(ack));
    }

    private void sendPresenceSnapshot(WebSocketSession session, UUID roomId) throws IOException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "PRESENCE_SNAPSHOT");
        payload.put("roomId", roomId.toString());
        ArrayNode online = payload.putArray("online");
        for (PresenceService.OnlineUser user : presenceService.onlineInRoom(roomId)) {
            ObjectNode item = online.addObject();
            item.put("userId", user.userId().toString());
            item.put("username", user.username());
        }
        SessionRegistry.send(session, objectMapper.writeValueAsString(payload));
    }

    private void sendAck(WebSocketSession session, Message saved, String requestId) throws IOException {
        ObjectNode ack = objectMapper.createObjectNode();
        ack.put("type", "ACK");
        ack.put("messageId", saved.getId().toString());
        ack.put("roomId", saved.getRoomId().toString());
        ack.put("sequenceNumber", saved.getSequenceNumber());
        putRequestId(ack, requestId);
        SessionRegistry.send(session, objectMapper.writeValueAsString(ack));
    }

    private void publishPresence(UUID roomId, UUID userId, String username, String status) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "PRESENCE");
        payload.put("roomId", roomId.toString());
        payload.put("userId", userId.toString());
        payload.put("username", username);
        payload.put("status", status);
        publisher.publish(new ChatEvent(ChatEventKind.PRESENCE, roomId, null, payload));
    }

    private void emitOfflineForSession(WebSocketSession session) {
        UUID userId;
        String username;
        try {
            userId = sessionRegistry.requireUser(session);
            username = sessionRegistry.username(session);
        } catch (IllegalStateException ex) {
            return;
        }
        Set<UUID> rooms = sessionRegistry.roomsOf(session);
        sessionRegistry.removeSession(session);
        for (UUID roomId : rooms) {
            if (presenceService.leave(roomId, userId)) {
                publishPresence(roomId, userId, username, "OFFLINE");
            }
        }
    }

    private void heartbeatPresence(WebSocketSession session) {
        try {
            presenceService.heartbeat(sessionRegistry.requireUser(session), sessionRegistry.roomsOf(session));
        } catch (IllegalStateException ignored) {
            // session not registered
        }
    }

    private ObjectNode messageNode(Message saved, String requestId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "MESSAGE");
        payload.put("messageId", saved.getId().toString());
        payload.put("roomId", saved.getRoomId().toString());
        payload.put("senderId", saved.getSenderId().toString());
        payload.put("content", saved.getContent());
        payload.put("timestamp", saved.getCreatedAt().toString());
        payload.put("sequenceNumber", saved.getSequenceNumber());
        putRequestId(payload, requestId);
        return payload;
    }

    private ObjectNode messageNode(MessageResponse message, String requestId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "MESSAGE");
        payload.put("messageId", message.messageId().toString());
        payload.put("roomId", message.roomId().toString());
        payload.put("senderId", message.senderId().toString());
        payload.put("content", message.content());
        payload.put("timestamp", message.timestamp().toString());
        payload.put("sequenceNumber", message.sequenceNumber());
        putRequestId(payload, requestId);
        return payload;
    }

    private UUID requireRoomId(JsonNode root) {
        String roomId = text(root, "roomId");
        if (roomId == null || roomId.isBlank()) {
            throw ApiException.badRequest("roomId is required");
        }
        try {
            return UUID.fromString(roomId);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("roomId must be a valid UUID");
        }
    }

    private long afterSequence(JsonNode root) {
        if (root == null || !root.has("afterSequence") || root.get("afterSequence").isNull()) {
            return 0L;
        }
        JsonNode node = root.get("afterSequence");
        if (node.isNumber()) {
            return Math.max(node.asLong(), 0L);
        }
        try {
            return Math.max(Long.parseLong(node.asText()), 0L);
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest("afterSequence must be a number");
        }
    }

    private void sendError(WebSocketSession session, String requestId, String code, String message) throws IOException {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("type", "ERROR");
        error.put("code", code);
        error.put("message", message);
        putRequestId(error, requestId);
        SessionRegistry.send(session, objectMapper.writeValueAsString(error));
    }

    private static void putRequestId(ObjectNode node, String requestId) {
        if (requestId != null) {
            node.put("requestId", requestId);
        }
    }

    private static String text(JsonNode root, String field) {
        if (root == null || !root.has(field) || root.get(field).isNull()) {
            return null;
        }
        return root.get(field).asText();
    }
}
