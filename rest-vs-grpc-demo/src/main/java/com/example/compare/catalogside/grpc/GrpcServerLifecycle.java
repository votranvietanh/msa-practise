package com.example.compare.catalogside.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionServiceV1;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Phía REST, Spring Boot TỰ mở Tomcat (port 8090) nhờ spring-boot-starter-web - không phải
 * viết dòng nào. Phía gRPC không có auto-config sẵn trong Spring Boot nên phải tự start
 * server (xem giải thích chi tiết ở catalog-service/.../GrpcServerLifecycle.java).
 *
 * grpc.server.port=0 -> hệ điều hành tự chọn port trống (dùng trong test), đọc lại qua getPort().
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private final ProductQueryGrpcService service;
    private final int configuredPort;

    private Server server;
    private volatile boolean running = false;

    public GrpcServerLifecycle(ProductQueryGrpcService service,
                               @Value("${grpc.server.port:9095}") int configuredPort) {
        this.service = service;
        this.configuredPort = configuredPort;
    }

    @Override
    public void start() {
        try {
            server = ServerBuilder.forPort(configuredPort)
                    .addService(service)
                    .addService(ProtoReflectionServiceV1.newInstance())
                    .build()
                    .start();
            running = true;
            System.out.println("[gRPC] ProductQueryService đang lắng nghe port " + server.getPort());
        } catch (IOException e) {
            throw new IllegalStateException("Không khởi động được gRPC server ở port " + configuredPort, e);
        }
    }

    @Override
    public void stop() {
        if (server != null) {
            server.shutdown();
            running = false;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return server.getPort();
    }
}
