package com.example.catalog.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Configuration
public class JwtDecoderConfig {

    /**
     * Cùng 1 loại JwtDecoder mà order-service/payment-service dùng (ở đó Spring Boot tự dựng từ
     * application.yml) - ở đây không có web nên tự dựng tay:
     *  - withJwkSetUri: tải khoá CÔNG KHAI của auth-service để verify chữ ký, Nimbus tự cache
     *    và tự tải lại khi gặp token ký bằng khoá mới (kid lạ). Tải LƯỜI ở lần decode đầu tiên,
     *    nên catalog-service khởi động được dù auth-service chưa chạy.
     *  - createDefaultWithIssuer: kiểm tra hạn dùng (exp/nbf) VÀ issuer (iss) - bỏ qua bước
     *    issuer thì token hợp lệ do BẤT KỲ nơi nào dùng cùng loại khoá phát ra đều được nhận.
     */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${auth.jwk-set-uri}") String jwkSetUri,
                                  @Value("${auth.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
