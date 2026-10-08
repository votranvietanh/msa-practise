package com.example.catalog.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
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
 *   3. ServerInterceptors.interceptForward(service, a, b, c) - bọc service bằng các
 *      interceptor theo THỨ TỰ LIỆT KÊ: a chạy trước tiên khi có request tới (lớp ngoài
 *      cùng), rồi b, rồi c, rồi mới tới service - giống model "middleware onion" quen thuộc.
 *
 *      CẠM BẪY: hàm cùng họ tên ServerInterceptors.intercept(service, a, b, c) làm NGƯỢC
 *      LẠI - interceptor CUỐI CÙNG trong danh sách chạy trước. Bản đầu của file này dùng
 *      intercept() nhưng comment lại viết như thể là thứ tự thuận (comment sai, thứ tự thật
 *      bị đảo). Thứ tự ở đây có hậu quả bảo mật thật: JwtAuthInterceptor phải chạy TRƯỚC
 *      RateLimitInterceptor thì rate limit mới biết người gọi là ai. Có test khoá cứng điều
 *      này: InterceptorOrderTest.
 *   4. ProtoReflectionServiceV1 - bật Server Reflection (giao thức v1 ổn định, thay cho
 *      ProtoReflectionService/v1alpha đã deprecated), cho phép `grpcurl -plaintext
 *      localhost:9090 list` liệt kê được toàn bộ service/method mà KHÔNG cần có sẵn
 *      file .proto trong tay (rất tiện để test tay, KHÔNG nên bật ở production vì lộ
 *      toàn bộ API surface cho bất kỳ ai có thể kết nối tới). Lưu ý: service reflection
 *      này được đăng ký RIÊNG, KHÔNG đi qua JwtAuthInterceptor - ai kết nối được cũng xem
 *      được cấu trúc API dù không có token (còn GỌI API thì vẫn bị chặn).
 *
 * implements SmartLifecycle: cách Spring quản lý 1 tài nguyên "chạy nền" (start lúc app
 * khởi động, stop lúc app tắt) đúng chuẩn, thay vì tự start() trong @PostConstruct rồi
 * không có chỗ nào gọi shutdown() sạch sẽ khi ứng dụng dừng.
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private final CatalogGrpcService catalogGrpcService;
    private final LoggingInterceptor loggingInterceptor;
    private final JwtAuthInterceptor jwtAuthInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final int port;

    private Server server;
    private volatile boolean running = false;

    public GrpcServerLifecycle(CatalogGrpcService catalogGrpcService,
                                LoggingInterceptor loggingInterceptor,
                                JwtAuthInterceptor jwtAuthInterceptor,
                                RateLimitInterceptor rateLimitInterceptor,
                                @Value("${grpc.server.port:9090}") int port) {
        this.catalogGrpcService = catalogGrpcService;
        this.loggingInterceptor = loggingInterceptor;
        this.jwtAuthInterceptor = jwtAuthInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.port = port;
    }

    /**
     * CatalogService đã được bọc các interceptor. Tách thành method riêng (package-private) để
     * test kiểm tra được ĐÚNG đoạn wiring thật này mà không phải mở cổng TCP.
     *
     * Thứ tự: log (ngoài cùng, thấy cả request bị từ chối) -> xác thực/phân quyền -> rate limit
     * theo người gọi -> service.
     */
    ServerServiceDefinition protectedService() {
        return ServerInterceptors.interceptForward(
                catalogGrpcService, loggingInterceptor, jwtAuthInterceptor, rateLimitInterceptor);
    }

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(port)
                    .addService(protectedService())
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();

            running = true;
            keepJvmAlive(server);
            System.out.println("[gRPC] CatalogService đang lắng nghe port " + port
                    + " (thử: grpcurl -plaintext localhost:" + port + " list)");
        } catch (IOException e) {
            throw new IllegalStateException("Không khởi động được gRPC server ở port " + port, e);
        }
    }

    /**
     * Thread các gRPC server tự tạo là DAEMON - JVM không tính chúng là lý do để ở lại. App này
     * không có web server (Tomcat vốn giữ sống app Spring Boot thông thường), nên nếu không có
     * thêm gì, hàm main() chạy xong là JVM thoát NGAY, server vừa mở đã bị đóng (bản đầu của
     * class này mắc đúng lỗi đó mà unit test không bắt được - chỉ lộ ra khi chạy thật).
     *
     * Cách chuẩn trong mọi ví dụ gRPC: 1 thread KHÔNG-daemon chỉ việc chờ server kết thúc
     * (awaitTermination). Khi stop() gọi shutdown(), lời chờ này trả về và thread tự chết.
     */
    private void keepJvmAlive(Server runningServer) {
        Thread awaiter = new Thread(() -> {
            try {
                runningServer.awaitTermination();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "grpc-server-awaiter");
        awaiter.setDaemon(false);
        awaiter.start();
    }

    @Override
    public void stop() {
        if (server != null) {
            // shutdown() = graceful: không nhận request MỚI, nhưng đợi request đang xử lý
            // xong rồi mới đóng hẳn - tránh cắt ngang response đang gửi dở cho client khi app
            // đang tắt. awaitTermination có thời hạn để không treo mãi nếu có RPC kẹt.
            server.shutdown();
            try {
                if (!server.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            running = false;
            System.out.println("[gRPC] CatalogService đã dừng");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
