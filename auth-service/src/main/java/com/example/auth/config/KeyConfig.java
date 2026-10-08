package com.example.auth.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.util.UUID;

@Configuration
public class KeyConfig {

    /**
     * Cặp khoá RSA BẤT ĐỐI XỨNG: auth-service giữ khoá RIÊNG để KÝ token; các service khác
     * chỉ lấy khoá CÔNG KHAI (qua /oauth2/jwks) để KIỂM TRA chữ ký. Hệ quả quan trọng:
     * service khác có kiểm tra được token, nhưng KHÔNG thể tự phát hành token giả - khác
     * hẳn kiểu HS256 dùng chung 1 secret, nơi bất kỳ service nào biết secret đều giả mạo
     * được token của người khác.
     *
     * Khoá sinh MỚI mỗi lần khởi động (đơn giản cho demo): restart auth-service thì token
     * cũ hết hiệu lực. Production nạp khoá từ KMS/keystore và xoay khoá có kế hoạch
     * (kid trong header giúp nhiều khoá cùng tồn tại trong giai đoạn chuyển tiếp).
     */
    @Bean
    public RSAKey rsaSigningKey() throws JOSEException {
        return new RSAKeyGenerator(2048)
                .keyID(UUID.randomUUID().toString())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .generate();
    }

    @Bean
    public JwtEncoder jwtEncoder(RSAKey rsaSigningKey) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaSigningKey)));
    }

    /** BCrypt: băm mật khẩu có "salt" ngẫu nhiên và cố tình chậm, chống dò mật khẩu hàng loạt. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
