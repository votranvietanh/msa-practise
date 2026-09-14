package com.example.catalog.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Cố tình KHÔNG dùng thư viện tích hợp sẵn (net.devh:grpc-spring-boot-starter) để bạn
 * thấy rõ 1 gRPC server thật sự được khởi động ra sao - đây là phần "core" của gRPC,
 * không có gì auto-magic cả:
 *   1. ServerBuilder.forPort(port) - mở 1 cổng TCP, gRPC luôn chạy trên HTTP/2.
 *   2. addService(...) - đăng ký implementation (CatalogGrpcService) cho service khai
 *      báo trong .proto. Có thể đăng ký NHIỀU service trên cùng 1 port.
 *   3. ServerInterceptors.intercept(service, interceptor1, interceptor2, ...) - bọc
 *      service bằng các interceptor, chạy theo thứ tự liệt kê (interceptor ĐẦU TIÊN
 *      trong danh sách là lớp NGOÀI CÙNG, chạy trước tiên khi có request tới, và chạy
 *      SAU CÙNG khi response đi ra - giống model "middleware onion" quen thuộc).
 *   4. ProtoReflectionServiceV1 - bật Server Reflection (giao thức v1 ổn định, thay cho
 *      ProtoReflectionService/v1alpha đã deprecated), cho phép `grpcurl -plaintext
 *      localhost:9090 list` liệt kê được toàn bộ service/method mà KHÔNG cần có sẵn
 *      file .proto trong tay (rất tiện để test tay, KHÔNG nên bật ở production vì lộ
 *      toàn bộ API surface cho bất kỳ ai có thể kết nối tới).
 *
 * implements SmartLifecycle: cách Spring quản lý 1 tài nguyên "chạy nền" (start lúc app
 * khởi động, stop lúc app tắt) đúng chuẩn, thay vì tự start() trong @PostConstruct rồi
 * không có chỗ nào gọi shutdown() sạch sẽ khi ứng dụng dừng.
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private final CatalogGrpcService catalogGrpcService;
    private final LoggingInterceptor loggingInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final int port;

    private Server server;
    private volatile boolean running = false;

    public GrpcServerLifecycle(CatalogGrpcService catalogGrpcService,
                                LoggingInterceptor loggingInterceptor,
                                RateLimitInterceptor rateLimitInterceptor,
                                @Value("${grpc.server.port:9090}") int port) {
        this.catalogGrpcService = catalogGrpcService;
        this.loggingInterceptor = loggingInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.port = port;
    }

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(port)
                    .addService(ServerInterceptors.intercept(catalogGrpcService, loggingInterceptor, rateLimitInterceptor))
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();

            running = true;
            System.out.println("[gRPC] CatalogService đang lắng nghe port " + port
                    + " (thử: grpcurl -plaintext localhost:" + port + " list)");
        } catch (IOException e) {
            throw new IllegalStateException("Không khởi động được gRPC server ở port " + port, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            // shutdown() = graceful: không nhận request MỚI, nhưng đợi request đang xử lý
            // xong (tối đa awaitTermination) rồi mới đóng hẳn - tránh cắt ngang response
            // đang gửi dở cho client khi app đang tắt.
            server.shutdown();
            running = false;
            System.out.println("[gRPC] CatalogService đã dừng");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
