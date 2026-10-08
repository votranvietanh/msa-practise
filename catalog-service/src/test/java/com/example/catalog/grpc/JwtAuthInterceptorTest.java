package com.example.catalog.grpc;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test xác thực + phân quyền của gRPC qua in-process server thật, với JWT thật (ký bằng khoá
 * sinh trong test). StubService chỉ "phản chiếu" người gọi ra response để test thấy được
 * CallerContext có được ghi đúng hay không.
 */
class JwtAuthInterceptorTest {

    private static class StubService extends CatalogServiceGrpc.CatalogServiceImplBase {
        @Override
        public void createProduct(CreateProductRequest request, StreamObserver<ProductDto> responseObserver) {
            responseObserver.onNext(ProductDto.newBuilder().setSku(CallerContext.CALLER_ID.get()).build());
            responseObserver.onCompleted();
        }

        @Override
        public void getProduct(GetProductRequest request, StreamObserver<ProductDto> responseObserver) {
            responseObserver.onNext(ProductDto.newBuilder().setSku(request.getSku()).build());
            responseObserver.onCompleted();
        }
    }

    private Server server;
    private ManagedChannel channel;
    private CatalogServiceGrpc.CatalogServiceBlockingStub stub;

    private void startWith(JwtDecoder decoder) throws Exception {
        String name = "jwt-auth-test-" + System.nanoTime();
        server = InProcessServerBuilder.forName(name)
                .directExecutor()
                .addService(ServerInterceptors.interceptForward(new StubService(), new JwtAuthInterceptor(decoder)))
                .build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        stub = CatalogServiceGrpc.newBlockingStub(channel);
    }

    @BeforeEach
    void setUp() throws Exception {
        startWith(TestTokens.decoder());
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    private Status.Code codeOf(Runnable call) {
        return assertThrows(StatusRuntimeException.class, call::run).getStatus().getCode();
    }

    private static CreateProductRequest createRequest() {
        return CreateProductRequest.newBuilder().setSku("SKU-1").build();
    }

    // ---------- UNAUTHENTICATED ----------

    @Test
    void khongCoHeaderAuthorization_UNAUTHENTICATED() {
        assertThat(codeOf(() -> stub.createProduct(createRequest()))).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void khongPhaiSchemeBearer_UNAUTHENTICATED() {
        var basic = TestTokens.withAuthorization(stub, "Basic YWxpY2U6cGFzcw==");
        assertThat(codeOf(() -> basic.createProduct(createRequest()))).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void tokenRac_UNAUTHENTICATED() {
        var garbage = TestTokens.withAuthorization(stub, "Bearer khong.phai.jwt");
        assertThat(codeOf(() -> garbage.createProduct(createRequest()))).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void tokenHetHan_UNAUTHENTICATED() {
        String expired = TestTokens.sign(TestTokens.KEY, TestTokens.ISSUER, "svc-a", "catalog:write",
                Instant.now().minusSeconds(3600));
        var client = TestTokens.withAuthorization(stub, "Bearer " + expired);
        assertThat(codeOf(() -> client.createProduct(createRequest()))).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void tokenKyBangKhoaLa_UNAUTHENTICATED() {
        String forged = TestTokens.sign(TestTokens.OTHER_KEY, TestTokens.ISSUER, "svc-a", "catalog:write",
                Instant.now().plusSeconds(300));
        var client = TestTokens.withAuthorization(stub, "Bearer " + forged);
        assertThat(codeOf(() -> client.createProduct(createRequest()))).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    @Test
    void tokenSaiIssuer_UNAUTHENTICATED() {
        String wrongIssuer = TestTokens.sign(TestTokens.KEY, "http://ke-xau.example", "svc-a", "catalog:write",
                Instant.now().plusSeconds(300));
        var client = TestTokens.withAuthorization(stub, "Bearer " + wrongIssuer);
        assertThat(codeOf(() -> client.createProduct(createRequest()))).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    // ---------- PERMISSION_DENIED ----------

    @Test
    void tokenHopLeNhungThieuScope_PERMISSION_DENIED() {
        // Có catalog:read nhưng CreateProduct cần catalog:write
        var readOnly = TestTokens.withAuthorization(stub, "Bearer " + TestTokens.valid("svc-ro", "catalog:read"));

        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, () -> readOnly.createProduct(createRequest()));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        assertThat(e.getStatus().getDescription()).contains("catalog:write");
    }

    @Test
    void tokenKhongCoClaimScope_PERMISSION_DENIED() {
        String noScope = TestTokens.sign(TestTokens.KEY, TestTokens.ISSUER, "svc-x", null, Instant.now().plusSeconds(300));
        var client = TestTokens.withAuthorization(stub, "Bearer " + noScope);
        assertThat(codeOf(() -> client.createProduct(createRequest()))).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    // ---------- thành công ----------

    @Test
    void tokenDungScope_duocPhep_vaCallerContextGhiDungNguoiGoi() {
        var client = TestTokens.withAuthorization(stub, "Bearer " + TestTokens.valid("svc-a", "catalog:read catalog:write"));

        ProductDto response = client.createProduct(createRequest());

        assertThat(response.getSku()).isEqualTo("svc-a"); // StubService trả lại CallerContext.CALLER_ID
    }

    @Test
    void moiRpcCoScopeRiengDuocKiemTraDocLap() {
        var client = TestTokens.withAuthorization(stub, "Bearer " + TestTokens.valid("svc-ro", "catalog:read"));

        // GetProduct chỉ cần catalog:read -> qua
        assertThat(client.getProduct(GetProductRequest.newBuilder().setSku("SKU-9").build()).getSku()).isEqualTo("SKU-9");
        // CreateProduct cần catalog:write -> chặn
        assertThat(codeOf(() -> client.createProduct(createRequest()))).isEqualTo(Status.Code.PERMISSION_DENIED);
    }

    // ---------- lỗi hạ tầng ----------

    @Test
    void khongTaiDuocKhoaTuAuthService_UNAVAILABLE_khongBaoNhamTokenSai() throws Exception {
        JwtDecoder brokenDecoder = mock(JwtDecoder.class);
        when(brokenDecoder.decode(anyString())).thenThrow(new JwtException("Couldn't retrieve remote JWK set"));
        tearDown();
        startWith(brokenDecoder);

        var client = TestTokens.withAuthorization(stub, "Bearer bat.ky.token");

        assertThat(codeOf(() -> client.createProduct(createRequest()))).isEqualTo(Status.Code.UNAVAILABLE);
    }

    @Test
    void decoderBaoTokenXau_vanLaUNAUTHENTICATED() throws Exception {
        JwtDecoder decoder = mock(JwtDecoder.class);
        when(decoder.decode(anyString())).thenThrow(new BadJwtException("token xấu"));
        tearDown();
        startWith(decoder);

        var client = TestTokens.withAuthorization(stub, "Bearer bat.ky.token");

        assertThat(codeOf(() -> client.createProduct(createRequest()))).isEqualTo(Status.Code.UNAUTHENTICATED);
    }

    // ---------- bảng quyền ----------

    @Test
    void moiRpcKhaiBaoTrongProtoDeuCoScopeYeuCau_khongRpcNaoBiQuenMoToang() {
        for (var method : CatalogServiceGrpc.getServiceDescriptor().getMethods()) {
            assertThat(JwtAuthInterceptor.requiredScopeFor(method.getFullMethodName()))
                    .as("RPC %s chưa được khai báo scope trong JwtAuthInterceptor", method.getFullMethodName())
                    .isPresent();
        }
    }
}
