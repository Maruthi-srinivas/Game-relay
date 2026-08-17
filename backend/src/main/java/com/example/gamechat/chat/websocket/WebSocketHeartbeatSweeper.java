package com.example.gamechat.chat.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;

@Component
public class WebSocketHeartbeatSweeper {

    private static final Logger log = LoggerFactory.getLogger(WebSocketHeartbeatSweeper.class);
    private static final CloseStatus IDLE = new CloseStatus(4000, "idle timeout");

    private final SessionRegistry sessionRegistry;
    private final long heartbeatTimeoutMs;

    public WebSocketHeartbeatSweeper(
            SessionRegistry sessionRegistry,
            @Value("${app.chat.heartbeat-timeout-ms}") long heartbeatTimeoutMs
    ) {
        this.sessionRegistry = sessionRegistry;
        this.heartbeatTimeoutMs = heartbeatTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${app.chat.heartbeat-sweep-ms}")
    public void sweep() {
        Instant cutoff = Instant.now().minusMillis(heartbeatTimeoutMs);
        for (WebSocketSession session : sessionRegistry.idleSessions(cutoff)) {
            try {
                session.close(IDLE);
            } catch (IOException ex) {
                log.debug("Failed to close idle session {}", session.getId(), ex);
            }
        }
    }
}
