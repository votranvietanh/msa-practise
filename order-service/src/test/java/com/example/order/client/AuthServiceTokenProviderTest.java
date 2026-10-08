package com.example.order.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuthServiceTokenProviderTest {

    /** Đồng hồ giả tự chỉnh được - để test "token hết hạn" mà không phải ngủ thật. */
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-07T00:00:00Z");

        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final String TOKEN_URL = "http://auth.test/oauth2/token";

    private MockRestServiceServer server;
    private MutableClock clock;
    private AuthServiceTokenProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock();
        provider = new AuthServiceTokenProvider(builder, "http://auth.test",
                "order-service", "secret-demo", "ledger:read", clock);
    }

    private void expectTokenRequest(String accessToken, long expiresIn) {
        server.expect(once(), requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "client_credentials",
                        "client_id", "order-service",
                        "client_secret", "secret-demo",
                        "scope", "ledger:read")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"" + accessToken + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + "}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void goiNhieuLanTrongHanDung_chiXinTokenMotLan() {
        expectTokenRequest("tok-1", 300);

        assertThat(provider.getToken()).isEqualTo("tok-1");
        assertThat(provider.getToken()).isEqualTo("tok-1");

        server.verify(); // đúng 1 request tới auth-service dù gọi getToken() 2 lần
    }

    @Test
    void tokenSapHetHan_tuXinTokenMoi() {
        expectTokenRequest("tok-1", 300);
        expectTokenRequest("tok-2", 300);

        assertThat(provider.getToken()).isEqualTo("tok-1");
        clock.advanceSeconds(280); // còn 20s < 30s "đệm" an toàn -> coi như hết hạn
        assertThat(provider.getToken()).isEqualTo("tok-2");

        server.verify();
    }

    @Test
    void invalidate_epXinTokenMoiDuTokenCuConHan() {
        expectTokenRequest("tok-1", 300);
        expectTokenRequest("tok-2", 300);

        provider.getToken();
        provider.invalidate();

        assertThat(provider.getToken()).isEqualTo("tok-2");
        server.verify();
    }

    @Test
    void authServiceTuChoi_nemLoiRoRang() {
        server.expect(once(), requestTo(TOKEN_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\"}"));

        assertThatThrownBy(() -> provider.getToken())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("service token");
    }
}
