package com.example.catalog.grpc;

import com.example.catalog.redis.LeaderboardService;
import com.example.catalog.redis.ProductEventPublisher;
import com.example.catalog.redis.RateLimiter;
import com.example.catalog.redis.RedisProductWatchBridge;
import com.example.catalog.redis.StockService;
import com.example.catalog.repository.ProductRepository;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Thứ tự interceptor là chuyện sống còn với bảo mật: xác thực phải chạy TRƯỚC rate limit (để
 * rate limit biết người gọi là ai) và trước service. Test này có 2 phần:
 *   1. Ghi lại hành vi THẬT của 2 hàm hay bị nhầm (intercept vs interceptForward) để không ai
 *      phải tin vào comment/trí nhớ.
 *   2. Kiểm tra wiring THẬT của GrpcServerLifecycle bằng hậu quả quan sát được.
 */
class InterceptorOrderTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    private static class StubService extends CatalogServiceGrpc.CatalogServiceImplBase {
        @Override
        public void getProduct(GetProductRequest request, StreamObserver<ProductDto> responseObserver) {
            responseObserver.onNext(ProductDto.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void createProduct(CreateProductRequest request, StreamObserver<ProductDto> responseObserver) {
            responseObserver.onNext(ProductDto.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    private static ServerInterceptor recording(String name, List<String> log) {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                log.add(name);
                return next.startCall(call, headers);
            }
        };
    }

    private CatalogServiceGrpc.CatalogServiceBlockingStub serve(ServerServiceDefinition definition) throws Exception {
        String name = "order-test-" + System.nanoTime();
        server = InProcessServerBuilder.forName(name).directExecutor().addService(definition).build().start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        return CatalogServiceGrpc.newBlockingStub(channel);
    }

    private List<String> orderOfExecution(Function<List<String>, ServerServiceDefinition> wrap) throws Exception {
        List<String> log = new ArrayList<>();
        serve(wrap.apply(log)).getProduct(GetProductRequest.getDefaultInstance());
        return log;
    }

    @Test
    void interceptForward_interceptorLietKeTruocChayTruoc() throws Exception {
        List<String> order = orderOfExecution(log -> ServerInterceptors.interceptForward(
                (BindableService) new StubService(),
                recording("log", log), recording("auth", log), recording("rate-limit", log)));

        assertThat(order).containsExactly("log", "auth", "rate-limit");
    }

    @Test
    void intercept_thuong_thuTuNguocLai_interceptorCuoiChayTruoc() throws Exception {
        List<String> order = orderOfExecution(log -> ServerInterceptors.intercept(
                (BindableService) new StubService(),
                recording("log", log), recording("auth", log), recording("rate-limit", log)));

        assertThat(order).containsExactly("rate-limit", "auth", "log");
    }

    @Test
    void wiringThatCuaGrpcServerLifecycle_xacThucChayTruocRateLimit() throws Exception {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.tryAcquire(any(), anyInt(), any())).thenReturn(true);

        CatalogGrpcService service = new CatalogGrpcService(
                mock(ProductRepository.class), mock(StockService.class), mock(LeaderboardService.class),
                mock(ProductEventPublisher.class), mock(RedisProductWatchBridge.class));
        GrpcServerLifecycle lifecycle = new GrpcServerLifecycle(
                service, new LoggingInterceptor(), new JwtAuthInterceptor(TestTokens.decoder()),
                new RateLimitInterceptor(rateLimiter), 0);

        var stub = serve(lifecycle.protectedService());
        var authorized = TestTokens.withAuthorization(stub, "Bearer " + TestTokens.valid("svc-a", "catalog:write"));

        // CreateProduct (có rate limit); không quan tâm kết quả nghiệp vụ, chỉ quan sát rate limit được hỏi với key nào
        try {
            authorized.createProduct(CreateProductRequest.newBuilder().setSku("SKU-1").setName("n").build());
        } catch (RuntimeException ignored) {
            // mock repository/stock có thể gây lỗi nghiệp vụ - không liên quan tới điều đang kiểm tra
        }

        // Nếu rate limit chạy TRƯỚC xác thực, CallerContext còn trống -> key là "...:anonymous".
        // Key có "svc-a" chứng minh JwtAuthInterceptor đã chạy trước và ghi người gọi vào Context.
        verify(rateLimiter).tryAcquire(
                eq("catalog.CatalogService/CreateProduct:svc-a"), anyInt(), eq(Duration.ofSeconds(10)));
    }
}
