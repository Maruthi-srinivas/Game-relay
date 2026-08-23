package com.example.gamechat.room.repository;

import com.example.gamechat.room.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    List<Report> findByRoom_IdOrderByCreatedAtDesc(UUID roomId);
}
