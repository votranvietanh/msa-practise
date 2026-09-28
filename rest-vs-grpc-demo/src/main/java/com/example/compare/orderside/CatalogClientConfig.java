package com.example.compare.orderside;

import com.example.compare.orderside.grpc.GrpcCatalogClient;
import com.example.compare.orderside.rest.RestCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Nối dây: tạo 1 OrderQuoteService cho MỖI giao thức. Thực tế chỉ chọn 1 trong 2; demo giữ
 * cả 2 để gọi song song và so sánh qua GET /api/v1/orders/quote?via=rest|grpc.
 */
@Configuration
public class CatalogClientConfig {

    /**
     * ManagedChannel = 1 kết nối HTTP/2 dùng lâu dài, chia sẻ cho MỌI request (HTTP/2 cho phép
     * nhiều request song song trên cùng 1 kết nối - "multiplexing"). Tạo 1 lần, dùng suốt đời
     * app, đóng khi app tắt (destroyMethod). Tạo channel mới cho mỗi request là lỗi hiệu năng.
     */
    @Bean(destroyMethod = "shutdown")
    public ManagedChannel catalogChannel(@Value("${catalog-client.grpc-host}") String host,
                                         @Value("${catalog-client.grpc-port}") int port) {
        return ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext() // tắt TLS: chỉ dùng khi chạy local
                .build();
    }

    @Bean
    public OrderQuoteService restOrderQuoteService(@Value("${catalog-client.rest-base-url}") String baseUrl,
                                                   @Value("${catalog-client.timeout}") Duration timeout,
                                                   ObjectMapper objectMapper) {
        return new OrderQuoteService(new RestCatalogClient(baseUrl, timeout, objectMapper));
    }

    @Bean
    public OrderQuoteService grpcOrderQuoteService(ManagedChannel catalogChannel,
                                                   @Value("${catalog-client.timeout}") Duration timeout) {
        return new OrderQuoteService(new GrpcCatalogClient(catalogChannel, timeout));
    }
}
