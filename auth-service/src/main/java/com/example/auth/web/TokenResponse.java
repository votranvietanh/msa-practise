package com.example.auth.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Tên field snake_case theo chuẩn OAuth2 (RFC 6749), để client OAuth2 bất kỳ đọc được. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("token_type") String tokenType,
        @JsonProperty("expires_in") long expiresIn,
        @JsonProperty("scope") String scope) {
}
