package com.example.gamechat.chat.websocket;

import com.example.gamechat.auth.security.UserPrincipal;
import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.chat.service.ChatService;
import com.example.gamechat.common.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.UUID;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final SessionRegistry sessionRegistry;
    private final ChatService chatService;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(
            SessionRegistry sessionRegistry,
            ChatService chatService,
            ObjectMapper objectMapper
    ) {
        this.sessionRegistry = sessionRegistry;
        this.chatService = chatService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UserPrincipal principal = (UserPrincipal) session.getAttributes().get("user");
        sessionRegistry.register(session, principal.userId());
        ObjectNode connected = objectMapper.createObjectNode();
        connected.put("type", "CONNECTED");
        connected.put("userId", principal.userId().toString());
        connected.put("username", principal.username());
        SessionRegistry.send(session, objectMapper.writeValueAsString(connected));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
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
        sessionRegistry.removeSession(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket transport error session={}", session.getId(), exception);
        sessionRegistry.removeSession(session);
    }

    private void handleJoin(WebSocketSession session, JsonNode root, String requestId) throws IOException {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        chatService.requireMember(roomId, userId);
        sessionRegistry.joinRoom(session, roomId);
        ObjectNode ack = objectMapper.createObjectNode();
        ack.put("type", "JOINED");
        ack.put("roomId", roomId.toString());
        putRequestId(ack, requestId);
        SessionRegistry.send(session, objectMapper.writeValueAsString(ack));
    }

    private void handleLeave(WebSocketSession session, JsonNode root, String requestId) throws IOException {
        UUID roomId = requireRoomId(root);
        sessionRegistry.leaveRoom(session, roomId);
        ObjectNode ack = objectMapper.createObjectNode();
        ack.put("type", "LEFT");
        ack.put("roomId", roomId.toString());
        putRequestId(ack, requestId);
        SessionRegistry.send(session, objectMapper.writeValueAsString(ack));
    }

    private void handleSend(WebSocketSession session, JsonNode root, String requestId) throws IOException {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        String content = text(root, "content");
        Message saved = chatService.sendMessage(userId, roomId, content);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "MESSAGE");
        payload.put("messageId", saved.getId().toString());
        payload.put("roomId", saved.getRoomId().toString());
        payload.put("senderId", saved.getSenderId().toString());
        payload.put("content", saved.getContent());
        payload.put("timestamp", saved.getCreatedAt().toString());
        putRequestId(payload, requestId);
        String json = objectMapper.writeValueAsString(payload);
        for (WebSocketSession memberSession : sessionRegistry.sessionsInRoom(roomId)) {
            try {
                SessionRegistry.send(memberSession, json);
            } catch (IOException ex) {
                log.warn("Failed to send message to session {}", memberSession.getId(), ex);
            }
        }
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
