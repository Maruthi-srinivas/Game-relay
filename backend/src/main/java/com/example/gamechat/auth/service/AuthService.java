package com.example.gamechat.auth.service;

import com.example.gamechat.auth.dto.AuthResponse;
import com.example.gamechat.auth.dto.LoginRequest;
import com.example.gamechat.auth.dto.RegisterRequest;
import com.example.gamechat.auth.entity.RefreshToken;
import com.example.gamechat.auth.entity.User;
import com.example.gamechat.auth.repository.RefreshTokenRepository;
import com.example.gamechat.auth.repository.UserRepository;
import com.example.gamechat.auth.security.JwtService;
import com.example.gamechat.auth.security.TokenDenylist;
import com.example.gamechat.auth.security.UserPrincipal;
import com.example.gamechat.chat.service.RateLimitService;
import com.example.gamechat.common.exception.ApiException;
import com.example.gamechat.room.service.RoomService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {

    public record IssuedAuth(AuthResponse response, String refreshToken, String accessToken) {
    }

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenDenylist tokenDenylist;
    private final RoomService roomService;
    private final RateLimitService rateLimitService;
    private final long refreshExpirationMs;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            TokenDenylist tokenDenylist,
            RoomService roomService,
            RateLimitService rateLimitService,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenDenylist = tokenDenylist;
        this.roomService = roomService;
        this.rateLimitService = rateLimitService;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    @Transactional
    public IssuedAuth register(RegisterRequest request, String clientKey) {
        rateLimitService.checkAuth(clientKey);
        if (userRepository.existsByUsernameIgnoreCase(request.username())) {
            throw ApiException.conflict("Username already taken");
        }
        if (userRepository.existsByEmailIgnoreCase(request.email())) {
            throw ApiException.conflict("Email already registered");
        }
        User user = new User();
        user.setUsername(request.username());
        user.setEmail(request.email().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setStatus("ACTIVE");
        userRepository.save(user);
        roomService.autoJoinGlobal(user.getId());
        return issue(user, UUID.randomUUID());
    }

    @Transactional
    public IssuedAuth login(LoginRequest request, String clientKey) {
        rateLimitService.checkAuth(clientKey);
        User user = userRepository.findByUsernameIgnoreCase(request.username())
                .orElseThrow(() -> ApiException.unauthorized("Invalid username or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid username or password");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw ApiException.forbidden("Account is not active");
        }
        roomService.autoJoinGlobal(user.getId());
        return issue(user, UUID.randomUUID());
    }

    @Transactional
    public IssuedAuth refresh(String rawRefresh) {
        if (rawRefresh == null || rawRefresh.isBlank()) {
            throw ApiException.unauthorized("Refresh token required");
        }
        RefreshToken current = refreshTokenRepository.findByTokenHash(hash(rawRefresh))
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        if (current.getRevokedAt() != null || current.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.revokeFamily(current.getFamilyId(), Instant.now());
            throw ApiException.unauthorized("Refresh token expired or revoked");
        }
        current.setRevokedAt(Instant.now());
        User user = current.getUser();
        if (!"ACTIVE".equals(user.getStatus())) {
            throw ApiException.forbidden("Account is not active");
        }
        return issue(user, current.getFamilyId());
    }

    @Transactional
    public void logout(String rawRefresh, UserPrincipal principal, String accessToken) {
        denyAccess(principal, accessToken);
        if (rawRefresh != null && !rawRefresh.isBlank()) {
            refreshTokenRepository.findByTokenHash(hash(rawRefresh)).ifPresent(token ->
                    refreshTokenRepository.revokeFamily(token.getFamilyId(), Instant.now())
            );
        }
    }

    public void denyAccess(UserPrincipal principal, String accessToken) {
        if (principal == null || principal.jti() == null || accessToken == null) {
            return;
        }
        tokenDenylist.deny(principal.jti(), jwtService.expiration(accessToken));
    }

    private IssuedAuth issue(User user, UUID familyId) {
        String access = jwtService.createToken(user.getId(), user.getUsername());
        String refreshRaw = UUID.randomUUID() + UUID.randomUUID().toString();
        RefreshToken stored = new RefreshToken();
        stored.setUser(user);
        stored.setTokenHash(hash(refreshRaw));
        stored.setFamilyId(familyId);
        stored.setExpiresAt(Instant.now().plusMillis(refreshExpirationMs));
        refreshTokenRepository.save(stored);
        AuthResponse response = new AuthResponse(
                access,
                jwtService.getExpirationMs(),
                user.getId(),
                user.getUsername()
        );
        return new IssuedAuth(response, refreshRaw, access);
    }

    private static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
