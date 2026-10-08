package com.example.order.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ServiceAuthInterceptorTest {

    /** Provider giả: mỗi lần "xin mới" trả tok-1, tok-2, ... để thấy rõ token nào được gửi đi. */
    private static class CountingProvider implements ServiceTokenProvider {
        private final AtomicInteger issued = new AtomicInteger();
        private final AtomicInteger invalidations = new AtomicInteger();
        private String current;

        @Override public synchronized String getToken() {
            if (current == null) current = "tok-" + issued.incrementAndGet();
            return current;
        }
        @Override public synchronized void invalidate() { current = null; invalidations.incrementAndGet(); }
    }

    private MockRestServiceServer server;
    private CountingProvider provider;
    private RestClient client;

    @BeforeEach
    void setUp() {
        provider = new CountingProvider();
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://payment.test")
                .requestInterceptor(new ServiceAuthInterceptor(provider));
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    @Test
    void tuDinhBearerTokenVaoMoiRequest() {
        server.expect(requestTo("http://payment.test/payments/ledger"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String body = client.get().uri("/payments/ledger").retrieve().body(String.class);

        assertThat(body).isEqualTo("[]");
        server.verify();
    }

    @Test
    void gap401_boTokenCu_xinMoi_thuLaiDungMotLan() {
        server.expect(requestTo("http://payment.test/payments/ledger"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("http://payment.test/payments/ledger"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok-2"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String body = client.get().uri("/payments/ledger").retrieve().body(String.class);

        assertThat(body).isEqualTo("[]");
        assertThat(provider.invalidations.get()).isEqualTo(1);
        server.verify();
    }

    @Test
    void van401SauKhiThuLai_khongLapVoTan_traLoiVeChoNguoiGoi() {
        server.expect(requestTo("http://payment.test/payments/ledger"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo("http://payment.test/payments/ledger"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.get().uri("/payments/ledger").retrieve().body(String.class))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);

        assertThat(provider.invalidations.get()).isEqualTo(1); // chỉ thử lại 1 lần, không hơn
        server.verify();
    }
}
