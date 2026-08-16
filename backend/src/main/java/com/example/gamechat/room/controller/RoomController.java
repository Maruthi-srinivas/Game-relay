package com.example.gamechat.room.controller;

import com.example.gamechat.auth.security.UserPrincipal;
import com.example.gamechat.chat.websocket.SessionRegistry;
import com.example.gamechat.room.dto.CreateRoomRequest;
import com.example.gamechat.room.dto.MemberResponse;
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
    private final SessionRegistry sessionRegistry;

    public RoomController(RoomService roomService, SessionRegistry sessionRegistry) {
        this.roomService = roomService;
        this.sessionRegistry = sessionRegistry;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomResponse create(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateRoomRequest request
    ) {
        return roomService.create(principal.userId(), request);
    }

    @GetMapping
    public List<RoomResponse> listMine(@AuthenticationPrincipal UserPrincipal principal) {
        return roomService.listForUser(principal.userId());
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
            @PathVariable UUID roomId
    ) {
        return roomService.join(roomId, principal.userId());
    }

    @PostMapping("/{roomId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId
    ) {
        roomService.leave(roomId, principal.userId());
        sessionRegistry.removeUserFromRoom(principal.userId(), roomId);
    }

    @GetMapping("/{roomId}/members")
    public List<MemberResponse> members(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID roomId
    ) {
        return roomService.listMembers(roomId, principal.userId());
    }
}
