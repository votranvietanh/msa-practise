package com.example.order.config;

import com.example.order.client.ServiceAuthInterceptor;
import com.example.order.client.ServiceTokenProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * RestClient: HTTP client đồng bộ kiểu fluent, có từ Spring 6.1 / Boot 3.2, thay thế
     * RestTemplate (đã vào chế độ maintenance, Spring không thêm tính năng mới cho nó nữa).
     *
     * CHỈ dùng bean này cho tác vụ đối soát/báo cáo (xem RestPaymentLedgerClient) - đọc dữ
     * liệu 1 lần khi có người gọi GET /orders/reconciliation, KHÔNG dùng để giao tiếp
     * nghiệp vụ giữa các service trong luồng Saga chính. Luồng Saga (order.created,
     * payment.success...) vẫn phải 100% qua RabbitMQ để giữ đúng nguyên tắc: xử lý bất
     * đồng bộ, không phụ thuộc Payment Service phải đang online tại đúng thời điểm gọi.
     *
     * requestInterceptor(ServiceAuthInterceptor): mọi request qua client này tự mang theo
     * service token (xem ServiceAuthInterceptor) - payment-service từ chối request không có.
     */
    @Bean
    public RestClient paymentServiceRestClient(@Value("${payment-service.base-url}") String baseUrl,
                                                ServiceTokenProvider tokenProvider) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(new ServiceAuthInterceptor(tokenProvider))
                .build();
    }
}
