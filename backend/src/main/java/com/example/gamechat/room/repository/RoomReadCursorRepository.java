package com.example.gamechat.room.repository;

import com.example.gamechat.room.entity.RoomMemberId;
import com.example.gamechat.room.entity.RoomReadCursor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RoomReadCursorRepository extends JpaRepository<RoomReadCursor, RoomMemberId> {

    List<RoomReadCursor> findByIdUserIdAndIdRoomIdIn(UUID userId, Collection<UUID> roomIds);
}
