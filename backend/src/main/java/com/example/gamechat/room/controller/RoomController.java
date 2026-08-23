package com.example.gamechat.room.controller;

import com.example.gamechat.auth.security.UserPrincipal;
import com.example.gamechat.chat.websocket.ChatWebSocketHandler;
import com.example.gamechat.room.dto.BanRequest;
import com.example.gamechat.room.dto.CreatePrivateRequest;
import com.example.gamechat.room.dto.CreateRoomRequest;
import com.example.gamechat.room.dto.InviteResponse;
import com.example.gamechat.room.dto.JoinRoomRequest;
import com.example.gamechat.room.dto.MemberResponse;
import com.example.gamechat.room.dto.ReportRequest;
import com.example.gamechat.room.dto.ReportResponse;
import com.example.gamechat.room.dto.RoomResponse;
import com.example.gamechat.room.service.RoomService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;
    private final ChatWebSocketHandler chatWebSocketHandler;

    public RoomController(RoomService roomService, ChatWebSocketHandler chatWebSocketHandler) {
        this.roomService = roomService;
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomResponse create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateRoomRequest request
    ) {
        return roomService.create(principal.userId(), request);
    }

    @PostMapping("/private")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomResponse createPrivate(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreatePrivateRequest request
    ) {
        return roomService.createPrivate(principal.userId(), request);
    }

    @GetMapping
    public List<RoomResponse> listMine(@AuthenticationPrincipal UserPrincipal principal) {
        return roomService.listForUser(principal.userId());
    }

    @PostMapping("/join")
    public RoomResponse joinByCode(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody JoinRoomRequest request
    ) {
        return roomService.joinByCode(principal.userId(), request.inviteCode());
    }

    @GetMapping("/{roomId}")
    public RoomResponse get(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId
    ) {
        return roomService.get(roomId, principal.userId());
    }

    @PostMapping("/{roomId}/join")
    public RoomResponse join(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @RequestBody(required = false) JoinRoomRequest request
    ) {
        return roomService.join(roomId, principal.userId(), request);
    }

    @PostMapping("/{roomId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId
    ) {
        roomService.leave(roomId, principal.userId());
        chatWebSocketHandler.dropUserFromRoom(principal.userId(), roomId);
    }

    @PostMapping("/{roomId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse invite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId
    ) {
        return roomService.createInvite(roomId, principal.userId());
    }

    @PostMapping("/{roomId}/members/{userId}/kick")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kick(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @PathVariable UUID userId
    ) {
        roomService.kick(roomId, principal.userId(), userId);
        chatWebSocketHandler.dropUserFromRoom(userId, roomId);
    }

    @PostMapping("/{roomId}/members/{userId}/mute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mute(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @PathVariable UUID userId
    ) {
        roomService.setMuted(roomId, principal.userId(), userId, true);
    }

    @PostMapping("/{roomId}/members/{userId}/unmute")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unmute(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @PathVariable UUID userId
    ) {
        roomService.setMuted(roomId, principal.userId(), userId, false);
    }

    @PostMapping("/{roomId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse report(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @Valid @RequestBody ReportRequest request
    ) {
        return roomService.report(roomId, principal.userId(), request);
    }

    @PostMapping("/{roomId}/members/{userId}/ban")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ban(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @PathVariable UUID userId,
            @RequestBody(required = false) BanRequest request
    ) {
        roomService.ban(roomId, principal.userId(), userId, request);
        chatWebSocketHandler.dropUserFromRoom(userId, roomId);
    }

    @PostMapping("/{roomId}/members/{userId}/unban")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unban(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @PathVariable UUID userId
    ) {
        roomService.unban(roomId, principal.userId(), userId);
    }

    @PostMapping("/{roomId}/members/{userId}/promote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void promote(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @PathVariable UUID userId
    ) {
        roomService.setRole(roomId, principal.userId(), userId, "MODERATOR");
    }

    @PostMapping("/{roomId}/members/{userId}/demote")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void demote(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @PathVariable UUID userId
    ) {
        roomService.setRole(roomId, principal.userId(), userId, "MEMBER");
    }

    @GetMapping("/{roomId}/reports")
    public List<ReportResponse> reports(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId
    ) {
        return roomService.listReports(roomId, principal.userId());
    }

    @PostMapping("/{roomId}/reports/{reportId}/resolve")
    public ReportResponse resolveReport(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId,
            @PathVariable UUID reportId
    ) {
        return roomService.resolveReport(roomId, principal.userId(), reportId);
    }

    @GetMapping("/{roomId}/members")
    public List<MemberResponse> members(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId
    ) {
        return roomService.listMembers(roomId, principal.userId());
    }
}
