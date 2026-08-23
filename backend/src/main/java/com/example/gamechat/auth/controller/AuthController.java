package com.example.gamechat.auth.controller;

import com.example.gamechat.auth.dto.AuthResponse;
import com.example.gamechat.auth.dto.LoginRequest;
import com.example.gamechat.auth.dto.RegisterRequest;
import com.example.gamechat.auth.security.RefreshCookieService;
import com.example.gamechat.auth.security.UserPrincipal;
import com.example.gamechat.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookieService refreshCookieService;

    public AuthController(AuthService authService, RefreshCookieService refreshCookieService) {
        this.authService = authService;
        this.refreshCookieService = refreshCookieService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        AuthService.IssuedAuth issued = authService.register(request, clientKey(httpRequest));
        refreshCookieService.set(httpResponse, issued.refreshToken());
        return issued.response();
    }

    @PostMapping("/login")
    public AuthResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        AuthService.IssuedAuth issued = authService.login(request, clientKey(httpRequest));
        refreshCookieService.set(httpResponse, issued.refreshToken());
        return issued.response();
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        AuthService.IssuedAuth issued = authService.refresh(refreshCookieService.read(httpRequest));
        refreshCookieService.set(httpResponse, issued.refreshToken());
        return issued.response();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse
    ) {
        authService.logout(refreshCookieService.read(httpRequest), principal, bearer(httpRequest));
        refreshCookieService.clear(httpResponse);
    }

    private static String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
