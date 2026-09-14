package com.example.catalog.grpc;

import io.grpc.*;
import org.springframework.stereotype.Component;

/**
 * ServerInterceptor: chạy TRƯỚC method thật của service cho MỌI RPC, giống filter/middleware
 * bên REST (Servlet Filter, Spring Interceptor) - chỗ hợp lý để làm log, auth, rate limit...
 * mà không phải lặp code đó trong từng method của CatalogGrpcService.
 *
 * Đăng ký nhiều interceptor thì chúng chạy theo thứ tự khai báo (interceptor đầu tiên
 * "bọc ngoài cùng" - xem GrpcServerLifecycle.start()).
 */
@Component
public class LoggingInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String method = call.getMethodDescriptor().getFullMethodName();
        long startedAt = System.currentTimeMillis();
        System.out.println("[gRPC] --> " + method);

        // SimpleForwardingServerCall: cách chuẩn để "chen" logic vào các callback của
        // 1 ServerCall (ở đây là close()) mà không phải tự implement lại toàn bộ interface.
        ServerCall<ReqT, RespT> loggingCall = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                long durationMs = System.currentTimeMillis() - startedAt;
                System.out.println("[gRPC] <-- " + method + " " + status.getCode() + " (" + durationMs + "ms)");
                super.close(status, trailers);
            }
        };

        return next.startCall(loggingCall, headers);
    }
}
