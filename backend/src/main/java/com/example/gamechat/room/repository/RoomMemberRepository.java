package com.example.gamechat.room.repository;

import com.example.gamechat.room.entity.RoomMember;
import com.example.gamechat.room.entity.RoomMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMemberId> {

    boolean existsByIdRoomIdAndIdUserId(UUID roomId, UUID userId);

    long countByIdRoomId(UUID roomId);

    List<RoomMember> findByIdRoomId(UUID roomId);

    @Query("""
            select m from RoomMember m
            join fetch m.user
            where m.id.roomId = :roomId
            order by m.joinedAt asc
            """)
    List<RoomMember> findWithUsersByRoomId(@Param("roomId") UUID roomId);

    @Query("""
            select m from RoomMember m
            join fetch m.room r
            join fetch r.owner
            where m.id.userId = :userId
            order by r.createdAt desc
            """)
    List<RoomMember> findWithRoomsByUserId(@Param("userId") UUID userId);
}
