package com.example.gamechat.auth.service;

import com.example.gamechat.auth.dto.AuthResponse;
import com.example.gamechat.auth.dto.LoginRequest;
import com.example.gamechat.auth.dto.RegisterRequest;
import com.example.gamechat.auth.entity.User;
import com.example.gamechat.auth.repository.UserRepository;
import com.example.gamechat.auth.security.JwtService;
import com.example.gamechat.common.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtService jwtService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    @Test
    void registerHashesPasswordAndReturnsToken() {
        RegisterRequest request = new RegisterRequest("alice", "alice@example.com", "password123");
        when(userRepository.existsByUsernameIgnoreCase("alice")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });
        when(jwtService.createToken(any(), any())).thenReturn("jwt-token");

        AuthResponse response = authService.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed");
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo("password123");
        assertThat(response.token()).isEqualTo("jwt-token");
        assertThat(response.username()).isEqualTo("alice");
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userRepository.existsByUsernameIgnoreCase("alice")).thenReturn(true);
        assertThatThrownBy(() -> authService.register(
                new RegisterRequest("alice", "alice@example.com", "password123")
        )).isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CONFLICT");
    }

    @Test
    void loginRejectsBadPassword() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        user.setPasswordHash("hashed");
        user.setStatus("ACTIVE");
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("alice", "wrong")))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("UNAUTHORIZED");
    }
}
