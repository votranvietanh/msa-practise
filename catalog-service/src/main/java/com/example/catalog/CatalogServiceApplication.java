package com.example.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Service này KHÔNG dùng spring-boot-starter-web - không có embedded Tomcat, không có
 * REST controller nào cả. API duy nhất là gRPC, tự khởi động qua GrpcServerLifecycle
 * (xem package grpc/) - đây là lý do app vẫn "chạy" (không thoát ngay) dù không có web
 * server: Netty (bên trong gRPC server) giữ vài non-daemon thread sống, cộng với
 * SmartLifecycle giữ context Spring không đóng sớm.
 */
@SpringBootApplication
public class CatalogServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
