package com.example.gamechat.chat.websocket;

import com.example.gamechat.auth.repository.UserRepository;
import com.example.gamechat.auth.security.JwtService;
import com.example.gamechat.auth.security.TokenDenylist;
import com.example.gamechat.auth.security.UserPrincipal;
import com.example.gamechat.chat.bus.ChatEvent;
import com.example.gamechat.chat.bus.ChatEventKind;
import com.example.gamechat.chat.bus.ChatEventPublisher;
import com.example.gamechat.chat.dto.AttachmentResponse;
import com.example.gamechat.chat.dto.MessageResponse;
import com.example.gamechat.chat.dto.ReactionResponse;
import com.example.gamechat.chat.dto.SyncBatch;
import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.chat.service.ChatService;
import com.example.gamechat.chat.service.MessageService;
import com.example.gamechat.chat.service.PresenceService;
import com.example.gamechat.common.exception.ApiException;
import com.example.gamechat.room.service.RoomService;
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
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final SessionRegistry sessionRegistry;
    private final ChatService chatService;
    private final MessageService messageService;
    private final PresenceService presenceService;
    private final ChatEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final TokenDenylist tokenDenylist;
    private final RoomService roomService;
    private final UserRepository userRepository;

    public ChatWebSocketHandler(
            SessionRegistry sessionRegistry,
            ChatService chatService,
            MessageService messageService,
            PresenceService presenceService,
            ChatEventPublisher publisher,
            ObjectMapper objectMapper,
            JwtService jwtService,
            TokenDenylist tokenDenylist,
            RoomService roomService,
            UserRepository userRepository
    ) {
        this.sessionRegistry = sessionRegistry;
        this.chatService = chatService;
        this.messageService = messageService;
        this.presenceService = presenceService;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.tokenDenylist = tokenDenylist;
        this.roomService = roomService;
        this.userRepository = userRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessionRegistry.markPending(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        sessionRegistry.touch(session);
        if (sessionRegistry.isRegistered(session)) {
            heartbeatPresence(session);
        }
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
        if (!sessionRegistry.isRegistered(session) && !"AUTH".equals(type)) {
            sendError(session, requestId, "UNAUTHORIZED", "Authenticate first");
            return;
        }
        try {
            switch (type) {
                case "AUTH" -> handleAuth(session, root, requestId);
                case "JOIN_ROOM" -> handleJoin(session, root, requestId);
                case "LEAVE_ROOM" -> handleLeave(session, root, requestId);
                case "SEND_MESSAGE" -> handleSend(session, root, requestId);
                case "PING" -> handlePing(session, requestId);
                case "TYPING" -> handleTyping(session, root, requestId);
                case "SET_PRESENCE" -> handleSetPresence(session, root, requestId);
                case "DELETE_MESSAGE" -> handleDelete(session, root, requestId);
                case "EDIT_MESSAGE" -> handleEdit(session, root, requestId);
                case "MESSAGE_ACK" -> handleAck(session, root);
                case "MARK_READ" -> handleMarkRead(session, root, requestId);
                case "ADD_REACTION" -> handleAddReaction(session, root, requestId);
                case "REMOVE_REACTION" -> handleRemoveReaction(session, root, requestId);
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

    private void handleAuth(WebSocketSession session, JsonNode root, String requestId) throws IOException {
        if (sessionRegistry.isRegistered(session)) {
            sendError(session, requestId, "BAD_REQUEST", "Already authenticated");
            return;
        }
        String token = text(root, "token");
        if (token == null || token.isBlank()) {
            sendError(session, requestId, "UNAUTHORIZED", "token is required");
            return;
        }
        UserPrincipal principal;
        try {
            principal = jwtService.parse(token);
        } catch (Exception ex) {
            sendError(session, requestId, "UNAUTHORIZED", "Invalid or expired token");
            return;
        }
        if (tokenDenylist.isDenied(principal.jti())) {
            sendError(session, requestId, "UNAUTHORIZED", "Token revoked");
            return;
        }
        session.getAttributes().put("user", principal);
        sessionRegistry.register(session, principal.userId(), principal.username());
        ObjectNode connected = objectMapper.createObjectNode();
        connected.put("type", "CONNECTED");
        connected.put("userId", principal.userId().toString());
        connected.put("username", principal.username());
        putRequestId(connected, requestId);
        SessionRegistry.send(session, objectMapper.writeValueAsString(connected));
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
            publishPresence(roomId, userId, sessionRegistry.username(session), presenceService.currentStatus(userId));
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
                publishPresence(roomId, userId, sessionRegistry.username(session), presenceService.currentStatus(userId));
            }
        }
        String content = text(root, "content");
        Message saved = chatService.sendMessage(userId, roomId, content, requestId);
        sendAck(session, saved, requestId);
        publisher.publishAfterCommit(new ChatEvent(
                ChatEventKind.MESSAGE,
                roomId,
                null,
                messageNode(messageService.toResponse(saved), requestId)
        ));
    }

    private void handlePing(WebSocketSession session, String requestId) throws IOException {
        ObjectNode pong = objectMapper.createObjectNode();
        pong.put("type", "PONG");
        putRequestId(pong, requestId);
        SessionRegistry.send(session, objectMapper.writeValueAsString(pong));
    }

    private void handleTyping(WebSocketSession session, JsonNode root, String requestId) {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        if (!sessionRegistry.isJoined(session, roomId) || roomService.isMuted(roomId, userId)) {
            return;
        }
        boolean isTyping = root.has("isTyping") && root.get("isTyping").asBoolean(false);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "TYPING");
        payload.put("roomId", roomId.toString());
        payload.put("userId", userId.toString());
        payload.put("username", sessionRegistry.username(session));
        payload.put("isTyping", isTyping);
        putRequestId(payload, requestId);
        publisher.publish(new ChatEvent(ChatEventKind.TYPING, roomId, session.getId(), payload));
    }

    private void handleSetPresence(WebSocketSession session, JsonNode root, String requestId) throws IOException {
        UUID userId = sessionRegistry.requireUser(session);
        String username = sessionRegistry.username(session);
        String status = presenceService.setStatus(userId, username, text(root, "status"));
        for (UUID roomId : presenceService.roomsOf(userId)) {
            publishPresence(roomId, userId, username, status);
        }
        ObjectNode ack = objectMapper.createObjectNode();
        ack.put("type", "PRESENCE_SET");
        ack.put("status", status);
        putRequestId(ack, requestId);
        SessionRegistry.send(session, objectMapper.writeValueAsString(ack));
    }

    private void handleDelete(WebSocketSession session, JsonNode root, String requestId) {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        UUID messageId = requireUuid(root, "messageId");
        chatService.deleteMessage(userId, roomId, messageId);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "MESSAGE_DELETED");
        payload.put("roomId", roomId.toString());
        payload.put("messageId", messageId.toString());
        putRequestId(payload, requestId);
        publisher.publish(new ChatEvent(ChatEventKind.MESSAGE_DELETED, roomId, null, payload));
    }

    private void handleEdit(WebSocketSession session, JsonNode root, String requestId) {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        UUID messageId = requireUuid(root, "messageId");
        Message edited = chatService.editMessage(userId, roomId, messageId, text(root, "content"));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "MESSAGE_EDITED");
        payload.put("roomId", roomId.toString());
        payload.put("messageId", messageId.toString());
        payload.put("content", edited.getContent());
        payload.put("editedAt", edited.getEditedAt().toString());
        putRequestId(payload, requestId);
        publisher.publish(new ChatEvent(ChatEventKind.MESSAGE_EDITED, roomId, null, payload));
    }

    private void handleAck(WebSocketSession session, JsonNode root) {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        long sequence = root.path("sequenceNumber").asLong(-1);
        if (sequence < 0 && root.hasNonNull("messageId")) {
            return;
        }
        if (sequence >= 0) {
            chatService.markRead(userId, roomId, sequence);
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("type", "READ");
            payload.put("roomId", roomId.toString());
            payload.put("userId", userId.toString());
            payload.put("sequenceNumber", sequence);
            publisher.publish(new ChatEvent(ChatEventKind.READ, roomId, session.getId(), payload));
        }
        ObjectNode delivery = objectMapper.createObjectNode();
        delivery.put("type", "DELIVERY");
        delivery.put("roomId", roomId.toString());
        delivery.put("userId", userId.toString());
        if (root.hasNonNull("messageId")) {
            delivery.put("messageId", root.get("messageId").asText());
        }
        publisher.publish(new ChatEvent(ChatEventKind.DELIVERY, roomId, session.getId(), delivery));
    }

    private void handleMarkRead(WebSocketSession session, JsonNode root, String requestId) {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        long sequence = root.path("sequenceNumber").asLong(0);
        chatService.markRead(userId, roomId, sequence);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "READ");
        payload.put("roomId", roomId.toString());
        payload.put("userId", userId.toString());
        payload.put("sequenceNumber", sequence);
        putRequestId(payload, requestId);
        publisher.publish(new ChatEvent(ChatEventKind.READ, roomId, session.getId(), payload));
    }

    private void handleAddReaction(WebSocketSession session, JsonNode root, String requestId) {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        UUID messageId = requireUuid(root, "messageId");
        String emoji = text(root, "emoji");
        chatService.addReaction(userId, roomId, messageId, emoji);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "REACTION");
        payload.put("action", "ADD");
        payload.put("roomId", roomId.toString());
        payload.put("messageId", messageId.toString());
        payload.put("userId", userId.toString());
        payload.put("emoji", emoji);
        putRequestId(payload, requestId);
        publisher.publish(new ChatEvent(ChatEventKind.REACTION, roomId, null, payload));
    }

    private void handleRemoveReaction(WebSocketSession session, JsonNode root, String requestId) {
        UUID roomId = requireRoomId(root);
        UUID userId = sessionRegistry.requireUser(session);
        UUID messageId = requireUuid(root, "messageId");
        String emoji = text(root, "emoji");
        chatService.removeReaction(userId, roomId, messageId, emoji);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "REACTION");
        payload.put("action", "REMOVE");
        payload.put("roomId", roomId.toString());
        payload.put("messageId", messageId.toString());
        payload.put("userId", userId.toString());
        payload.put("emoji", emoji);
        putRequestId(payload, requestId);
        publisher.publish(new ChatEvent(ChatEventKind.REACTION, roomId, null, payload));
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
            item.put("status", user.status());
            if (user.lastSeenAt() != null) {
                item.put("lastSeenAt", user.lastSeenAt().toString());
            }
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
        Instant lastSeen = presenceService.lastSeen(userId);
        if (lastSeen != null) {
            payload.put("lastSeenAt", lastSeen.toString());
        }
        publisher.publish(new ChatEvent(ChatEventKind.PRESENCE, roomId, null, payload));
    }

    private void emitOfflineForSession(WebSocketSession session) {
        UUID userId;
        String username;
        try {
            userId = sessionRegistry.requireUser(session);
            username = sessionRegistry.username(session);
        } catch (IllegalStateException ex) {
            sessionRegistry.removeSession(session);
            return;
        }
        Set<UUID> rooms = sessionRegistry.roomsOf(session);
        boolean lastSocket = !sessionRegistry.hasOtherSessions(userId, session);
        sessionRegistry.removeSession(session);
        for (UUID roomId : rooms) {
            if (presenceService.leave(roomId, userId)) {
                publishPresence(roomId, userId, username, "OFFLINE");
            }
        }
        if (lastSocket) {
            persistLastSeen(userId);
            presenceService.markOffline(userId, username);
        }
    }

    private void persistLastSeen(UUID userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastSeenAt(Instant.now());
            userRepository.save(user);
        });
    }

    private void heartbeatPresence(WebSocketSession session) {
        try {
            presenceService.heartbeat(sessionRegistry.requireUser(session), sessionRegistry.roomsOf(session));
        } catch (IllegalStateException ignored) {
            // session not registered
        }
    }

    private ObjectNode messageNode(Message saved, String requestId) {
        return messageNode(messageService.toResponse(saved), requestId);
    }

    private ObjectNode messageNode(MessageResponse message, String requestId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "MESSAGE");
        payload.put("messageId", message.messageId().toString());
        payload.put("roomId", message.roomId().toString());
        payload.put("senderId", message.senderId().toString());
        payload.put("content", message.content() == null ? "" : message.content());
        payload.put("timestamp", message.timestamp().toString());
        payload.put("sequenceNumber", message.sequenceNumber());
        if (message.editedAt() != null) {
            payload.put("editedAt", message.editedAt().toString());
        }
        putRequestId(payload, requestId != null ? requestId : message.requestId());
        ArrayNode attachments = payload.putArray("attachments");
        for (AttachmentResponse attachment : message.attachments()) {
            ObjectNode item = attachments.addObject();
            item.put("id", attachment.id().toString());
            item.put("contentType", attachment.contentType());
            item.put("originalName", attachment.originalName());
            item.put("sizeBytes", attachment.sizeBytes());
        }
        ArrayNode reactions = payload.putArray("reactions");
        for (ReactionResponse reaction : message.reactions()) {
            ObjectNode item = reactions.addObject();
            item.put("emoji", reaction.emoji());
            item.put("count", reaction.count());
            ArrayNode userIds = item.putArray("userIds");
            for (UUID userId : reaction.userIds()) {
                userIds.add(userId.toString());
            }
        }
        return payload;
    }

    private UUID requireRoomId(JsonNode root) {
        return requireUuid(root, "roomId");
    }

    private UUID requireUuid(JsonNode root, String field) {
        String value = text(root, field);
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " is required");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest(field + " must be a valid UUID");
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
