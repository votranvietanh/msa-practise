package com.example.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test đi qua đúng filter chain + controller thật của auth-service (không mock gì cả - service
 * này không phụ thuộc hạ tầng ngoài nên test được trọn vẹn), rồi kiểm tra token phát ra bằng
 * ĐÚNG cách các service khác sẽ làm: lấy khoá công khai từ /oauth2/jwks và verify chữ ký.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private NimbusJwtDecoder decoder;

    @BeforeEach
    void setUp() throws Exception {
        String jwksJson = mockMvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        RSAKey publicKey = (RSAKey) JWKSet.parse(jwksJson).getKeys().get(0);
        decoder = NimbusJwtDecoder.withPublicKey(publicKey.toRSAPublicKey()).build();
    }

    private JsonNode login(String username, String password, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult tokenRequest(String grantType, String clientId, String secret, String scope) throws Exception {
        var request = post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", grantType)
                .param("client_id", clientId)
                .param("client_secret", secret);
        if (scope != null) {
            request.param("scope", scope);
        }
        return mockMvc.perform(request).andReturn();
    }

    @Test
    void dangNhapDungMatKhau_nhanJwtKiemTraDuocBangKhoaCongKhai() throws Exception {
        JsonNode body = login("alice", "alice-pass", 200);

        assertThat(body.get("token_type").asText()).isEqualTo("Bearer");
        assertThat(body.get("expires_in").asLong()).isEqualTo(Duration.ofMinutes(15).toSeconds());

        Jwt jwt = decoder.decode(body.get("access_token").asText());
        assertThat(jwt.getSubject()).isEqualTo("U001");
        assertThat(jwt.getIssuer().toString()).isEqualTo("http://localhost:8090");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
    }

    @Test
    void saiMatKhauVaUserKhongTonTai_traVeCungMotLoi() throws Exception {
        JsonNode wrongPassword = login("alice", "sai-mat-khau", 401);
        JsonNode unknownUser = login("khong-ton-tai", "bat-ky", 401);

        // Cùng 1 nội dung lỗi: không để kẻ tấn công phân biệt "user có tồn tại hay không"
        assertThat(wrongPassword.get("error").asText()).isEqualTo("invalid_credentials");
        assertThat(unknownUser).isEqualTo(wrongPassword);
    }

    @Test
    void clientCredentials_khongNeuScope_capDuScopeDuocPhep() throws Exception {
        MvcResult result = tokenRequest("client_credentials", "catalog-demo-client", "catalog-demo-secret", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Jwt jwt = decoder.decode(body.get("access_token").asText());

        assertThat(jwt.getSubject()).isEqualTo("catalog-demo-client");
        assertThat(jwt.getClaimAsString("scope").split(" "))
                .containsExactlyInAnyOrder("catalog:read", "catalog:write", "catalog:reserve");
        assertThat(jwt.getClaimAsStringList("roles")).isNull();
    }

    @Test
    void clientCredentials_xinTapConScope_chiNhanDungPhanDaXin() throws Exception {
        MvcResult result = tokenRequest("client_credentials", "catalog-demo-client", "catalog-demo-secret", "catalog:read");

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("scope").asText()).isEqualTo("catalog:read");
    }

    @Test
    void clientCredentials_xinThuaScope_biTuChoiInvalidScope() throws Exception {
        MvcResult result = tokenRequest("client_credentials", "catalog-readonly-client",
                "catalog-readonly-secret", "catalog:read catalog:write");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("invalid_scope");
    }

    @Test
    void clientCredentials_saiSecret_bi401InvalidClient() throws Exception {
        MvcResult result = tokenRequest("client_credentials", "order-service", "sai-secret", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).contains("invalid_client");
    }

    @Test
    void grantTypeKhacClientCredentials_biTuChoi() throws Exception {
        MvcResult result = tokenRequest("password", "order-service", "order-service-secret", null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("unsupported_grant_type");
    }

    @Test
    void jwks_chiChuaKhoaCongKhai_khongLoKhoaRieng() throws Exception {
        String jwks = mockMvc.perform(get("/oauth2/jwks")).andReturn().getResponse().getContentAsString();
        JsonNode key = objectMapper.readTree(jwks).get("keys").get(0);

        assertThat(key.has("n")).isTrue();   // modulus công khai
        assertThat(key.has("e")).isTrue();   // exponent công khai
        assertThat(key.has("d")).isFalse();  // khoá riêng: tuyệt đối không được xuất hiện
        assertThat(key.has("p")).isFalse();
        assertThat(key.has("q")).isFalse();
    }

    @Test
    void duongDanKhongNamTrongDanhSachChoPhep_biChanHan() throws Exception {
        mockMvc.perform(get("/bat-ky-duong-dan-nao")).andExpect(status().isForbidden());
    }

    @Test
    void guiJsonNhungThieuTruong_khongLamSapServer() throws Exception {
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_credentials"));
    }
}
