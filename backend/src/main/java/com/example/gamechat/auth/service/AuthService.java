package com.example.gamechat.auth.service;

import com.example.gamechat.auth.dto.AuthResponse;
import com.example.gamechat.auth.dto.LoginRequest;
import com.example.gamechat.auth.dto.RegisterRequest;
import com.example.gamechat.auth.entity.User;
import com.example.gamechat.auth.repository.UserRepository;
import com.example.gamechat.auth.security.JwtService;
import com.example.gamechat.common.exception.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
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
        String token = jwtService.createToken(user.getId(), user.getUsername());
        return new AuthResponse(token, user.getId(), user.getUsername());
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsernameIgnoreCase(request.username())
                .orElseThrow(() -> ApiException.unauthorized("Invalid username or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("Invalid username or password");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw ApiException.forbidden("Account is not active");
        }
        String token = jwtService.createToken(user.getId(), user.getUsername());
        return new AuthResponse(token, user.getId(), user.getUsername());
    }
}
