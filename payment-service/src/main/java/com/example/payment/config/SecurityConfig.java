package com.example.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * payment-service chỉ có 1 nhóm REST là sổ cái giao dịch (/payments/ledger) - dữ liệu tài
 * chính nhạy cảm, và người gọi hợp lệ DUY NHẤT là service khác (order-service đối soát), không
 * phải người dùng cuối. Vì vậy yêu cầu SERVICE TOKEN mang scope "ledger:read".
 *
 * Claim "scope" trong JWT tự động thành quyền "SCOPE_ledger:read" nhờ converter mặc định của
 * Spring Security (không cần viết thêm code chuyển đổi như bên order-service, vốn còn phải đọc
 * claim "roles" của người dùng).
 *
 * Luồng Saga qua RabbitMQ KHÔNG đi qua filter này (không phải HTTP) - bảo vệ kênh đó là việc
 * của user/quyền trên RabbitMQ, 1 lớp phòng thủ khác.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Tài liệu API: mở cho tiện học; production nên tắt hoặc chỉ mở nội bộ.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Cho phép /error để lỗi 500 thật không bị luật denyAll bên dưới đổi thành 403
                        // (chi tiết ở order-service's SecurityConfig).
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/payments/ledger/**").hasAuthority("SCOPE_ledger:read")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }))
                .build();
    }
}
