package com.example.gamechat.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "room_read_cursors")
public class RoomReadCursor {

    @EmbeddedId
    private RoomMemberId id;

    @Column(name = "last_read_sequence", nullable = false)
    private long lastReadSequence;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public RoomMemberId getId() {
        return id;
    }

    public void setId(RoomMemberId id) {
        this.id = id;
    }

    public long getLastReadSequence() {
        return lastReadSequence;
    }

    public void setLastReadSequence(long lastReadSequence) {
        this.lastReadSequence = lastReadSequence;
    }
}
