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
 * Hạn mức tính RIÊNG CHO TỪNG NGƯỜI GỌI (key = tên method + định danh người gọi lấy từ
 * CallerContext do JwtAuthInterceptor ghi vào): 1 client spam không làm các client khác bị
 * chặn lây. Điều này chỉ có nghĩa khi JwtAuthInterceptor chạy TRƯỚC interceptor này - nếu
 * đảo thứ tự, mọi request đều thành "anonymous" và cả hệ thống lại dùng chung 1 hạn mức
 * (xem InterceptorOrderTest + GrpcServerLifecycle).
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
        String caller = CallerContext.CALLER_ID.get();
        String limitKey = method + ":" + (caller != null ? caller : "anonymous");

        if (LIMITED_METHOD.equals(method) && !rateLimiter.tryAcquire(limitKey, LIMIT, WINDOW)) {
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
