package com.example.gamechat.room.repository;

import com.example.gamechat.room.entity.RoomBan;
import com.example.gamechat.room.entity.RoomMemberId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RoomBanRepository extends JpaRepository<RoomBan, RoomMemberId> {

    boolean existsByIdRoomIdAndIdUserId(UUID roomId, UUID userId);
}
