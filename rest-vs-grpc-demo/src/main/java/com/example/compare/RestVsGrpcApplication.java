package com.example.compare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Để dễ chạy, CẢ 2 "service" được gói chung trong 1 process:
 *   - package catalogside: đóng vai Catalog Service (server) - mở REST ở port 8090 VÀ gRPC ở port 9095.
 *   - package orderside  : đóng vai Order Service (client) - gọi sang catalogside qua mạng thật
 *                          (localhost), KHÔNG gọi thẳng method Java.
 * Ở hệ thống thật đây là 2 project/2 container riêng; ranh giới giữa 2 package này chính là
 * ranh giới mạng giữa 2 microservice - orderside KHÔNG được import bất cứ gì từ catalogside.
 */
@SpringBootApplication
public class RestVsGrpcApplication {

    public static void main(String[] args) {
        SpringApplication.run(RestVsGrpcApplication.class, args);
    }
}
