package com.example.gamechat.auth.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RefreshCookieService {

    public static final String COOKIE_NAME = "refresh_token";

    private final boolean secure;
    private final long refreshExpirationMs;

    public RefreshCookieService(
            @Value("${jwt.refresh-cookie-secure:true}") boolean secure,
            @Value("${jwt.refresh-expiration-ms:604800000}") long refreshExpirationMs
    ) {
        this.secure = secure;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    public void set(HttpServletResponse response, String rawToken) {
        response.addHeader("Set-Cookie", header(rawToken, (int) (refreshExpirationMs / 1000)));
    }

    public void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", header("", 0));
    }

    public String read(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String header(String value, int maxAge) {
        StringBuilder builder = new StringBuilder();
        builder.append(COOKIE_NAME).append("=").append(value == null ? "" : value);
        builder.append("; Path=/api/auth; HttpOnly; SameSite=Lax; Max-Age=").append(maxAge);
        if (secure) {
            builder.append("; Secure");
        }
        return builder.toString();
    }
}
