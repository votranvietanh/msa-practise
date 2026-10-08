package com.example.order.controller;

import com.example.order.config.SecurityConfig;
import com.example.order.dto.OrderSummaryReport;
import com.example.order.dto.OrderStatusResponse;
import com.example.order.dto.ReconciliationReport;
import com.example.order.entity.Order;
import com.example.order.entity.OrderStatus;
import com.example.order.service.OrderQueryService;
import com.example.order.service.OrderService;
import com.example.order.service.ReconciliationService;
import com.example.order.service.ReportService;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test ĐI QUA ĐÚNG filter chain bảo mật thật (SecurityConfig): request mang JWT thật (ký bằng
 * 1 khoá RSA sinh ngay trong test), qua BearerTokenAuthenticationFilter -> JwtDecoder (kiểm
 * chữ ký/hạn/issuer) -> chuyển claim thành quyền -> luật phân quyền -> controller. Chỉ phần
 * "tải khoá công khai từ auth-service qua mạng" là được thay bằng khoá cục bộ.
 */
@WebMvcTest(controllers = {OrderController.class, ReportController.class, ReconciliationController.class})
@Import({SecurityConfig.class, OrderSecurityTest.LocalJwtConfig.class})
class OrderSecurityTest {

    private static final String ISSUER = "http://localhost:8090";
    private static final RSAKey SIGNING_KEY = newKey();
    private static final RSAKey OTHER_KEY = newKey();

    @TestConfiguration
    static class LocalJwtConfig {
        @Bean
        JwtDecoder jwtDecoder() throws JOSEException {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
            // Giống production: ngoài chữ ký còn kiểm tra hạn dùng và issuer.
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
            return decoder;
        }
    }

    @Autowired private MockMvc mockMvc;

    @MockitoBean private OrderService orderService;
    @MockitoBean private OrderQueryService orderQueryService;
    @MockitoBean private ReportService reportService;
    @MockitoBean private ReconciliationService reconciliationService;

    // ---------- helpers ----------

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").algorithm(JWSAlgorithm.RS256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(RSAKey key, String issuer, String subject, Instant expiresAt, Map<String, Object> claims) {
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .issuedAt(expiresAt.minus(Duration.ofMinutes(15)))
                .expiresAt(expiresAt);
        claims.forEach(builder::claim);
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, builder.build())).getTokenValue();
    }

    private static String bearerUser(String userId) {
        return "Bearer " + sign(SIGNING_KEY, ISSUER, userId, Instant.now().plusSeconds(600), Map.of("roles", List.of("USER")));
    }

    private static String bearerAdmin() {
        return "Bearer " + sign(SIGNING_KEY, ISSUER, "ADMIN-01", Instant.now().plusSeconds(600), Map.of("roles", List.of("ADMIN")));
    }

    private static String bearerService(String scope) {
        return "Bearer " + sign(SIGNING_KEY, ISSUER, "order-service", Instant.now().plusSeconds(600), Map.of("scope", scope));
    }

    private static final String ORDER_BODY = "{\"amount\":250000,\"items\":[{\"sku\":\"ITEM-01\",\"qty\":1}]}";

    // ---------- POST /orders ----------

    @Test
    void taoDon_khongCoToken_bi401() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void taoDon_userHopLe_userIdLayTuToken() throws Exception {
        when(orderService.createOrder(eq("U001"), any()))
                .thenReturn(new Order("ORD-1", "U001", 250_000L, List.of(), OrderStatus.PENDING));

        mockMvc.perform(post("/orders")
                        .header("Authorization", bearerUser("U001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ORDER_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.orderId").value("ORD-1"));
    }

    @Test
    void taoDon_clientGuiKemUserIdGiaMao_vanDungUserIdTrongToken() throws Exception {
        when(orderService.createOrder(eq("U001"), any()))
                .thenReturn(new Order("ORD-2", "U001", 250_000L, List.of(), OrderStatus.PENDING));

        // Cố tình đặt đơn "nhân danh" U999 bằng cách nhét userId vào body
        mockMvc.perform(post("/orders")
                        .header("Authorization", bearerUser("U001"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"U999\",\"amount\":250000,\"items\":[]}"))
                .andExpect(status().isAccepted());

        verify(orderService).createOrder(eq("U001"), any());
    }

    @Test
    void taoDon_adminKhongCoQuyenUser_bi403() throws Exception {
        mockMvc.perform(post("/orders")
                        .header("Authorization", bearerAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void taoDon_tokenCuaService_bi403() throws Exception {
        mockMvc.perform(post("/orders")
                        .header("Authorization", bearerService("ledger:read"))
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_BODY))
                .andExpect(status().isForbidden());
    }

    // ---------- token xấu ----------

    @Test
    void tokenRac_bi401() throws Exception {
        mockMvc.perform(get("/orders/ORD-1/status").header("Authorization", "Bearer khong.phai.jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDaHetHan_bi401() throws Exception {
        String expired = sign(SIGNING_KEY, ISSUER, "U001", Instant.now().minusSeconds(3600), Map.of("roles", List.of("USER")));

        mockMvc.perform(get("/orders/ORD-1/status").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenKyBangKhoaLa_bi401() throws Exception {
        String forged = sign(OTHER_KEY, ISSUER, "ADMIN-01", Instant.now().plusSeconds(600), Map.of("roles", List.of("ADMIN")));

        mockMvc.perform(get("/orders/report/summary").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenDungKhoaNhungSaiIssuer_bi401() throws Exception {
        String wrongIssuer = sign(SIGNING_KEY, "http://ke-xau.example", "U001", Instant.now().plusSeconds(600),
                Map.of("roles", List.of("USER")));

        mockMvc.perform(get("/orders/ORD-1/status").header("Authorization", "Bearer " + wrongIssuer))
                .andExpect(status().isUnauthorized());
    }

    // ---------- GET /orders/{id}/status (phân quyền theo dữ liệu) ----------

    @Test
    void xemStatus_chuDon_duocPhep() throws Exception {
        when(orderQueryService.getOwnerId("ORD-1")).thenReturn("U001");
        when(orderQueryService.getStatus("ORD-1")).thenReturn(new OrderStatusResponse("PENDING", null));

        mockMvc.perform(get("/orders/ORD-1/status").header("Authorization", bearerUser("U001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void xemStatus_donCuaNguoiKhac_tra404_khongLoRaDonCoTonTai() throws Exception {
        when(orderQueryService.getOwnerId("ORD-1")).thenReturn("U001");
        // BẮT BUỘC stub cả getStatus: nếu để mock trả null thì controller cũng ra 404 vì "không có
        // status", test sẽ xanh dù kiểm tra chủ đơn đã bị xoá (đã từng mắc đúng lỗi này - phát hiện
        // bằng cách cố ý xoá kiểm tra rồi xem test có đỏ không).
        when(orderQueryService.getStatus("ORD-1")).thenReturn(new OrderStatusResponse("PENDING", null));

        mockMvc.perform(get("/orders/ORD-1/status").header("Authorization", bearerUser("U002")))
                .andExpect(status().isNotFound());
    }

    @Test
    void xemStatus_adminXemDuocDonCuaBatKyAi() throws Exception {
        when(orderQueryService.getOwnerId("ORD-1")).thenReturn("U001");
        when(orderQueryService.getStatus("ORD-1")).thenReturn(new OrderStatusResponse("COMPLETED", null));

        mockMvc.perform(get("/orders/ORD-1/status").header("Authorization", bearerAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void xemStatus_donKhongTonTai_tra404() throws Exception {
        when(orderQueryService.getOwnerId("ORD-MA")).thenReturn(null);

        mockMvc.perform(get("/orders/ORD-MA/status").header("Authorization", bearerUser("U001")))
                .andExpect(status().isNotFound());
    }

    // ---------- báo cáo / đối soát: chỉ ADMIN ----------

    @Test
    void baoCao_userThuong_bi403() throws Exception {
        mockMvc.perform(get("/orders/report/summary").header("Authorization", bearerUser("U001")))
                .andExpect(status().isForbidden());
    }

    @Test
    void baoCao_admin_duocPhep() throws Exception {
        when(reportService.summarize()).thenReturn(new OrderSummaryReport(0, 0, 0, 0, 0, 0));

        mockMvc.perform(get("/orders/report/summary").header("Authorization", bearerAdmin()))
                .andExpect(status().isOk());
    }

    @Test
    void doiSoat_userThuong_bi403_vaKhongToken_bi401() throws Exception {
        mockMvc.perform(get("/orders/reconciliation").header("Authorization", bearerUser("U001")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/orders/reconciliation"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void doiSoat_admin_duocPhep() throws Exception {
        when(reconciliationService.reconcile()).thenReturn(new ReconciliationReport(0, 0, List.of()));

        mockMvc.perform(get("/orders/reconciliation").header("Authorization", bearerAdmin()))
                .andExpect(status().isOk());
    }

    // ---------- deny by default ----------

    @Test
    void duongDanChuaKhaiBaoQuyen_biChanDuDaDangNhap() throws Exception {
        mockMvc.perform(get("/orders/endpoint-moi-quen-khai-bao").header("Authorization", bearerAdmin()))
                .andExpect(status().isForbidden());
    }
}
