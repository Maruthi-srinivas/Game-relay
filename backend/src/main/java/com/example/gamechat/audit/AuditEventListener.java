package com.example.gamechat.audit;

import com.example.gamechat.audit.entity.AuditEvent;
import com.example.gamechat.audit.repository.AuditEventRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@Profile("audit")
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true")
public class AuditEventListener {

    private final AuditEventRepository auditEventRepository;

    public AuditEventListener(AuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
    }

    @KafkaListener(topics = {
            "chat.message.persisted",
            "chat.user.joined",
            "chat.user.left",
            "chat.moderation"
    }, groupId = "gamechat-audit")
    public void onEvent(@Header(KafkaHeaders.RECEIVED_TOPIC) String topic, String payload) {
        AuditEvent event = new AuditEvent();
        event.setTopic(topic);
        event.setPayload(payload);
        auditEventRepository.save(event);
    }
}
