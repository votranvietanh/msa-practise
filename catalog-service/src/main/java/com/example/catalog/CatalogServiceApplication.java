package com.example.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Service này KHÔNG dùng spring-boot-starter-web - không có embedded Tomcat, không có
 * REST controller nào cả. API duy nhất là gRPC, tự khởi động qua GrpcServerLifecycle
 * (xem package grpc/). Không có web server nào giữ JVM sống, nên GrpcServerLifecycle phải
 * tự tạo 1 thread KHÔNG-daemon chờ server (keepJvmAlive) - thiếu nó app khởi động xong là thoát ngay.
 */
@SpringBootApplication
public class CatalogServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
