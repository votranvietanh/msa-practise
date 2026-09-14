package com.example.catalog.grpc;

import com.example.catalog.redis.RateLimiter;
import io.grpc.*;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test qua in-process server thật (không mock ServerCall/Metadata thủ công) - dùng 1
 * service giả tối thiểu (StubService) để cô lập đúng hành vi cần test: interceptor có
 * chặn/cho qua request đúng theo RateLimiter hay không, không lẫn với logic nghiệp vụ
 * thật của CatalogGrpcService.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    private static class StubService extends CatalogServiceGrpc.CatalogServiceImplBase {
        @Override
        public void createProduct(CreateProductRequest request, StreamObserver<ProductDto> responseObserver) {
            responseObserver.onNext(ProductDto.newBuilder().setSku(request.getSku()).build());
            responseObserver.onCompleted();
        }

        @Override
        public void getProduct(GetProductRequest request, StreamObserver<ProductDto> responseObserver) {
            responseObserver.onNext(ProductDto.newBuilder().setSku(request.getSku()).build());
            responseObserver.onCompleted();
        }
    }

    @Mock private RateLimiter rateLimiter;

    private Server server;
    private ManagedChannel channel;
    private CatalogServiceGrpc.CatalogServiceBlockingStub stub;

    @BeforeEach
    void setUp() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter);
        String serverName = "ratelimit-test-" + System.nanoTime();

        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(ServerInterceptors.intercept(new StubService(), interceptor))
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        stub = CatalogServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    void choPhepRequest_khiRateLimiterChapNhan() {
        when(rateLimiter.tryAcquire(anyString(), eq(5), eq(Duration.ofSeconds(10)))).thenReturn(true);

        ProductDto response = stub.createProduct(CreateProductRequest.newBuilder().setSku("SKU-1").build());

        assertThat(response.getSku()).isEqualTo("SKU-1");
    }

    @Test
    void chanRequest_traVeResourceExhausted_khiRateLimiterTuChoi() {
        when(rateLimiter.tryAcquire(anyString(), eq(5), eq(Duration.ofSeconds(10)))).thenReturn(false);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.createProduct(CreateProductRequest.newBuilder().setSku("SKU-2").build()));

        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    }

    @Test
    void khongApDungRateLimit_choMethodKhacNgoaiCreateProduct() {
        ProductDto response = stub.getProduct(GetProductRequest.newBuilder().setSku("SKU-3").build());

        assertThat(response.getSku()).isEqualTo("SKU-3");
        verify(rateLimiter, never()).tryAcquire(anyString(), anyInt(), any());
    }
}
