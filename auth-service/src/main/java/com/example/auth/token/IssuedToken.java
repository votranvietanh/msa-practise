package com.example.auth.token;

/** Kết quả phát hành token (scope = null với token người dùng, vì người dùng dùng "roles"). */
public record IssuedToken(String accessToken, long expiresInSeconds, String scope) {
}
