package com.example.gamechat.room.repository;

import com.example.gamechat.room.entity.RoomInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RoomInviteRepository extends JpaRepository<RoomInvite, UUID> {

    Optional<RoomInvite> findByCodeIgnoreCase(String code);
}
