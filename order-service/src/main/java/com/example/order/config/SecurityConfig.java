package com.example.order.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.ArrayList;
import java.util.List;

/**
 * order-service là "resource server": nó KHÔNG tự đăng nhập ai cả, chỉ kiểm tra JWT do
 * auth-service ký. Chữ ký được verify bằng khoá công khai lấy từ spring.security.oauth2.
 * resourceserver.jwt.jwk-set-uri (xem application.yml) - Spring Boot tự dựng JwtDecoder.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless + token trong header (không cookie) -> không có "phiên" để CSRF lợi dụng.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Tài liệu API: để mở cho tiện học. PRODUCTION nên tắt hẳn hoặc chỉ mở nội bộ.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // Khi controller ném lỗi, Spring "chuyển tiếp" sang đường dẫn /error để dựng
                        // response. Nếu /error cũng bị denyAll, MỌI lỗi 500 thật sẽ bị biến thành 403
                        // (phát hiện khi chạy thật: Redis sập mà client lại thấy "cấm truy cập" -
                        // rất khó debug). /error chỉ trả status + path, không lộ nội dung lỗi.
                        .requestMatchers("/error").permitAll()
                        // Quy tắc cụ thể đặt TRƯỚC quy tắc chung (khớp từ trên xuống, khớp cái nào dùng cái đó).
                        .requestMatchers("/orders/report/**", "/orders/reconciliation").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/orders").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/orders/*/status").hasAnyRole("USER", "ADMIN")
                        // Deny by default: endpoint nào thêm sau mà quên khai báo quyền thì bị CHẶN,
                        // thay vì vô tình mở toang (an toàn khi quên, thay vì nguy hiểm khi quên).
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * Đổi nội dung JWT thành "quyền" mà Spring Security hiểu:
     *  - claim "scope" (token service)  -> SCOPE_ledger:read ...  (mặc định của Spring)
     *  - claim "roles" (token người dùng) -> ROLE_USER / ROLE_ADMIN  (tự thêm ở đây, vì
     *    Spring mặc định không đọc claim "roles")
     * hasRole("ADMIN") ở trên so khớp với ROLE_ADMIN.
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>(scopeConverter.convert(jwt));
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            }
            return authorities;
        });
        return converter;
    }
}
