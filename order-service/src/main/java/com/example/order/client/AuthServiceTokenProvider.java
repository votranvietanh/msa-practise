package com.example.order.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * OAuth2 client credentials: order-service tự đăng nhập vào auth-service BẰNG TƯ CÁCH 1
 * SERVICE (client_id + client_secret), nhận về JWT ngắn hạn rồi đính vào mỗi lời gọi sang
 * payment-service. Payment Service nhờ đó biết "người gọi là order-service, được phép đọc
 * ledger" thay vì chấp nhận bất kỳ ai gọi tới.
 *
 * Token được GIỮ LẠI dùng nhiều lần (cache trong bộ nhớ) cho tới khi sắp hết hạn - nếu xin
 * token mới cho MỖI request thì auth-service thành điểm nghẽn và mỗi lời gọi chậm gấp đôi.
 */
@Component
public class AuthServiceTokenProvider implements ServiceTokenProvider {

    /** Coi token là "hết hạn" sớm 30s so với thật, tránh gửi đi 1 token chết ngay lúc tới nơi (lệch đồng hồ, độ trễ mạng). */
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") long expiresIn) {}

    private final RestClient authClient;
    private final String clientId;
    private final String clientSecret;
    private final String scope;
    private final Clock clock;

    private String cachedToken;
    private Instant cachedExpiry = Instant.MIN;

    @Autowired
    public AuthServiceTokenProvider(RestClient.Builder restClientBuilder,
                                     @Value("${auth-service.base-url}") String baseUrl,
                                     @Value("${auth-service.client-id}") String clientId,
                                     @Value("${auth-service.client-secret}") String clientSecret,
                                     @Value("${auth-service.scope}") String scope) {
        this(restClientBuilder, baseUrl, clientId, clientSecret, scope, Clock.systemUTC());
    }

    AuthServiceTokenProvider(RestClient.Builder restClientBuilder, String baseUrl,
                              String clientId, String clientSecret, String scope, Clock clock) {
        this.authClient = restClientBuilder.baseUrl(baseUrl).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.clock = clock;
    }

    /** synchronized: nhiều request cùng lúc thấy token hết hạn chỉ được phép kéo theo ĐÚNG 1 lần xin token mới. */
    @Override
    public synchronized String getToken() {
        if (cachedToken == null || !clock.instant().isBefore(cachedExpiry)) {
            refresh();
        }
        return cachedToken;
    }

    @Override
    public synchronized void invalidate() {
        cachedToken = null;
        cachedExpiry = Instant.MIN;
    }

    private void refresh() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("scope", scope);

        try {
            TokenResponse response = authClient.post()
                    .uri("/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);

            if (response == null || response.accessToken() == null) {
                throw new IllegalStateException("auth-service trả về response rỗng khi xin service token");
            }
            cachedToken = response.accessToken();
            cachedExpiry = clock.instant().plusSeconds(response.expiresIn()).minus(EXPIRY_SKEW);
        } catch (RestClientException e) {
            throw new IllegalStateException("Không xin được service token từ auth-service", e);
        }
    }
}
