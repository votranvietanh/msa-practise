package com.example.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Gom toàn bộ cấu hình prefix "auth" trong application.yml vào 1 record bất biến -
 * Spring Boot tự bind (constructor binding) và tự đổi kiểu (chuỗi "PT15M" -> Duration).
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String issuer,
        Duration userTokenTtl,
        Duration serviceTokenTtl,
        List<UserAccount> users,
        List<ServiceClient> clients) {

    /** Tài khoản người dùng cuối. userId là định danh NỘI BỘ (sẽ nằm trong claim "sub"). */
    public record UserAccount(String username, String password, String userId, List<String> roles) {}

    /** "Tài khoản" của 1 service (OAuth2 client credentials). */
    public record ServiceClient(String clientId, String clientSecret, List<String> scopes) {}
}
