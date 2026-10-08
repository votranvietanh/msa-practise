package com.example.payment.controller;

import com.example.payment.config.SecurityConfig;
import com.example.payment.ledger.PaymentLedger;
import com.example.payment.ledger.PaymentTransaction;
import com.example.payment.ledger.TransactionType;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cùng cách làm với OrderSecurityTest: JWT thật ký bằng khoá cục bộ, đi qua filter chain thật. */
@WebMvcTest(PaymentLedgerController.class)
@Import({SecurityConfig.class, PaymentLedgerSecurityTest.LocalJwtConfig.class})
class PaymentLedgerSecurityTest {

    private static final String ISSUER = "http://localhost:8090";
    private static final RSAKey SIGNING_KEY = newKey();
    private static final RSAKey OTHER_KEY = newKey();

    @TestConfiguration
    static class LocalJwtConfig {
        @Bean
        JwtDecoder jwtDecoder() throws JOSEException {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PaymentLedger paymentLedger;

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String bearer(RSAKey key, String claimName, Object claimValue) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER).subject("order-service")
                .issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim(claimName, claimValue)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        String token = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)))
                .encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return "Bearer " + token;
    }

    @Test
    void khongCoToken_bi401() throws Exception {
        mockMvc.perform(get("/payments/ledger")).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenServiceDungScope_doc_duocLedger() throws Exception {
        when(paymentLedger.findAll()).thenReturn(List.of(
                new PaymentTransaction("ORD-1", "U001", 250_000L, TransactionType.CHARGE_SUCCESS, Instant.now())));

        mockMvc.perform(get("/payments/ledger").header("Authorization", bearer(SIGNING_KEY, "scope", "ledger:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value("ORD-1"));
    }

    @Test
    void tokenHopLeNhungThieuScope_bi403() throws Exception {
        mockMvc.perform(get("/payments/ledger").header("Authorization", bearer(SIGNING_KEY, "scope", "catalog:read")))
                .andExpect(status().isForbidden());
    }

    @Test
    void tokenCuaNguoiDung_khongCoScopeLedger_bi403() throws Exception {
        // Token người dùng (claim roles, không có scope) không đọc được sổ cái dù đã đăng nhập hợp lệ
        mockMvc.perform(get("/payments/ledger").header("Authorization", bearer(SIGNING_KEY, "roles", List.of("ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void theoOrderId_cungYeuCauScope() throws Exception {
        mockMvc.perform(get("/payments/ledger/ORD-1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/payments/ledger/ORD-1").header("Authorization", bearer(SIGNING_KEY, "scope", "ledger:read")))
                .andExpect(status().isOk());
    }

    @Test
    void tokenKyBangKhoaLa_bi401() throws Exception {
        mockMvc.perform(get("/payments/ledger").header("Authorization", bearer(OTHER_KEY, "scope", "ledger:read")))
                .andExpect(status().isUnauthorized());
    }
}
