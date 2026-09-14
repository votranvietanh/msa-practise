package com.example.catalog.grpc;

import com.example.catalog.redis.RateLimiter;
import io.grpc.*;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Chặn request NGAY TẠI TẦNG INTERCEPTOR (trước khi vào tới CatalogGrpcService.createProduct)
 * nếu vượt quá giới hạn - CatalogGrpcService không cần biết gì về rate limit, tách biệt rõ
 * "logic nghiệp vụ" khỏi "chính sách bảo vệ API" (Single Responsibility).
 *
 * Demo rate-limit theo TÊN METHOD (mọi client cùng chia sẻ 1 giới hạn) để đơn giản hoá -
 * hệ thống thật nên rate-limit theo API key/userId lấy từ Metadata (header gRPC), mỗi
 * client 1 giới hạn riêng.
 */
@Component
public class RateLimitInterceptor implements ServerInterceptor {

    private static final int LIMIT = 5;
    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final String LIMITED_METHOD = "catalog.CatalogService/CreateProduct";

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String method = call.getMethodDescriptor().getFullMethodName();

        if (LIMITED_METHOD.equals(method) && !rateLimiter.tryAcquire(method, LIMIT, WINDOW)) {
            // RESOURCE_EXHAUSTED: đúng status code chuẩn gRPC cho "vượt quota/rate limit"
            // (tương đương HTTP 429 Too Many Requests bên REST). call.close() kết thúc
            // RPC NGAY, không gọi tới CatalogGrpcService.createProduct() nữa.
            call.close(Status.RESOURCE_EXHAUSTED
                    .withDescription("Quá nhiều request CreateProduct, thử lại sau " + WINDOW.toSeconds() + "s"),
                    new Metadata());
            // Trả về listener "rỗng" (bỏ qua mọi callback tiếp theo) vì RPC đã bị đóng ở trên.
            return new ServerCall.Listener<>() {};
        }

        return next.startCall(call, headers);
    }
}
