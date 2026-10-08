package com.example.catalog.grpc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.grpc.Metadata;
import io.grpc.stub.AbstractStub;
import io.grpc.stub.MetadataUtils;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Instant;

/**
 * Đồ nghề test: tự ký JWT bằng khoá RSA sinh ngay trong test và dựng JwtDecoder tương ứng -
 * thay cho auth-service thật + việc tải JWKS qua mạng, nhưng vẫn đi đúng đường verify chữ ký/
 * hạn/issuer như production (cùng NimbusJwtDecoder + createDefaultWithIssuer).
 */
final class TestTokens {
    private TestTokens() {}

    static final String ISSUER = "http://localhost:8090";
    static final RSAKey KEY = newKey();
    static final RSAKey OTHER_KEY = newKey();

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sign(RSAKey key, String issuer, String subject, String scope, Instant expiresAt) {
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(issuer).subject(subject)
                .issuedAt(expiresAt.minusSeconds(300)).expiresAt(expiresAt);
        if (scope != null) {
            builder.claim("scope", scope);
        }
        JwtClaimsSet claims = builder.build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Token hợp lệ cho `subject` với đúng các scope cho trước. */
    static String valid(String subject, String scope) {
        return sign(KEY, ISSUER, subject, scope, Instant.now().plusSeconds(300));
    }

    /** Gắn header "authorization" vào mọi lời gọi của stub (giống cách client thật đính token). */
    static <T extends AbstractStub<T>> T withAuthorization(T stub, String headerValue) {
        Metadata metadata = new Metadata();
        metadata.put(JwtAuthInterceptor.AUTHORIZATION, headerValue);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }
}
