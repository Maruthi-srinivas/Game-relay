package com.example.gamechat.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${app.kafka.enabled:false}' == 'true' && '${app.kafka.log-listener:true}' == 'true'")
public class ChatEventLogListener {

    private static final Logger log = LoggerFactory.getLogger(ChatEventLogListener.class);

    @KafkaListener(topics = {
            "chat.message.persisted",
            "chat.user.joined",
            "chat.user.left",
            "chat.moderation"
    }, groupId = "gamechat-log")
    public void onEvent(String payload) {
        log.info("kafka event {}", payload);
    }
}
