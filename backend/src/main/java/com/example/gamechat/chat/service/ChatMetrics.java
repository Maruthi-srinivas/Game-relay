package com.example.gamechat.chat.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ChatMetrics {

    private final Counter messagesSent;

    public ChatMetrics(MeterRegistry meterRegistry) {
        this.messagesSent = Counter.builder("chat.messages.sent").register(meterRegistry);
    }

    public void recordMessageSent() {
        messagesSent.increment();
    }
}
