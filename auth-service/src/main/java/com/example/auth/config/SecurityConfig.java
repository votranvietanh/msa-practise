package com.example.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /**
     * Đây chính là các endpoint ĐỂ XÁC THỰC nên phải mở cho mọi người gọi (chưa có token
     * thì lấy đâu ra mà gửi). Mọi đường dẫn khác bị chặn hẳn (deny by default).
     *
     * csrf tắt: CSRF là tấn công lợi dụng cookie phiên của trình duyệt; API này stateless,
     * không dùng cookie/session nên không có gì để lợi dụng.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/login", "/oauth2/token", "/oauth2/jwks").permitAll()
                        // /error: nếu bị chặn, mọi lỗi 400/500 sẽ bị biến thành 403 sai lệch (vd gửi
                        // JSON hỏng phải ra 400 chứ không phải 403) - chi tiết ở order-service's SecurityConfig.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .build();
    }
}
