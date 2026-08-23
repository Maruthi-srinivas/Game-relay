package com.example.gamechat.chat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "message_reactions")
public class MessageReaction {

    @EmbeddedId
    private MessageReactionId id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public MessageReactionId getId() {
        return id;
    }

    public void setId(MessageReactionId id) {
        this.id = id;
    }
}
