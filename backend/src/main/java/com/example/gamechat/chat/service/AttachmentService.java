package com.example.gamechat.chat.service;

import com.example.gamechat.chat.entity.Message;
import com.example.gamechat.chat.entity.MessageAttachment;
import com.example.gamechat.chat.repository.MessageAttachmentRepository;
import com.example.gamechat.chat.repository.MessageRepository;
import com.example.gamechat.common.exception.ApiException;
import com.example.gamechat.kafka.ChatEventLog;
import com.example.gamechat.room.service.RoomService;
import com.example.gamechat.storage.ObjectStore;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

@Service
public class AttachmentService {

    private static final long MAX_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/gif",
            "application/pdf"
    );

    private final RoomService roomService;
    private final MessageService messageService;
    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final ObjectStore objectStore;
    private final ChatEventLog chatEventLog;
    private final ChatMetrics chatMetrics;
    private final RateLimitService rateLimitService;

    public AttachmentService(
            RoomService roomService,
            MessageService messageService,
            MessageRepository messageRepository,
            MessageAttachmentRepository attachmentRepository,
            ObjectStore objectStore,
            ChatEventLog chatEventLog,
            ChatMetrics chatMetrics,
            RateLimitService rateLimitService
    ) {
        this.roomService = roomService;
        this.messageService = messageService;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.objectStore = objectStore;
        this.chatEventLog = chatEventLog;
        this.chatMetrics = chatMetrics;
        this.rateLimitService = rateLimitService;
    }

    @Transactional
    public Message upload(UUID userId, UUID roomId, MultipartFile file, String caption) {
        roomService.requireMember(roomId, userId);
        if (roomService.isMuted(roomId, userId)) {
            throw ApiException.forbidden("You are muted in this room");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("File is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("File exceeds 5MB limit");
        }
        String contentType = file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType();
        if (!ALLOWED.contains(contentType)) {
            throw ApiException.badRequest("Unsupported file type");
        }
        rateLimitService.checkSend(userId);
        Message saved = messageService.save(roomId, userId, caption, null, true);
        String key = roomId + "/" + saved.getId() + "/" + UUID.randomUUID();
        try {
            objectStore.put(key, file.getBytes(), contentType);
        } catch (IOException ex) {
            throw ApiException.badRequest("Failed to read upload");
        }
        MessageAttachment attachment = new MessageAttachment();
        attachment.setMessageId(saved.getId());
        attachment.setObjectKey(key);
        attachment.setContentType(contentType);
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        attachment.setOriginalName(original.substring(0, Math.min(original.length(), 255)));
        attachment.setSizeBytes(file.getSize());
        attachmentRepository.save(attachment);
        chatEventLog.messagePersisted(saved);
        chatMetrics.recordMessageSent();
        return saved;
    }

    @Transactional(readOnly = true)
    public Download download(UUID userId, UUID attachmentId) {
        MessageAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> ApiException.notFound("Attachment not found"));
        Message message = messageRepository.findById(attachment.getMessageId())
                .orElseThrow(() -> ApiException.notFound("Attachment not found"));
        roomService.requireMember(message.getRoomId(), userId);
        return new Download(
                objectStore.get(attachment.getObjectKey()),
                attachment.getContentType(),
                attachment.getOriginalName(),
                attachment.getSizeBytes()
        );
    }

    public record Download(InputStream stream, String contentType, String originalName, long sizeBytes) {
    }
}
