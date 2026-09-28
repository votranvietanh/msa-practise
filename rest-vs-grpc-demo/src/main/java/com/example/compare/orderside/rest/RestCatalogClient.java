package com.example.compare.orderside.rest;

import com.example.compare.orderside.CatalogClient;
import com.example.compare.orderside.CatalogUnavailableException;
import com.example.compare.orderside.ProductNotFoundException;
import com.example.compare.orderside.ProductQuote;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Gọi Catalog Service qua REST. Để ý những thứ client REST PHẢI TỰ LÀM mà bản gRPC
 * (GrpcCatalogClient) được code sinh sẵn lo hộ:
 *   (1) Tự ghép URL + query param bằng chuỗi  -> gõ sai "/api/v1/product" chỉ lộ ra lúc chạy (404).
 *   (2) Tự viết lại DTO (ProductJson) khớp tên field với server -> lệch tên là nhận null/0 ÂM THẦM.
 *   (3) Tự đọc HTTP status + tự parse JSON body lỗi theo format team tự quy ước (ErrorJson).
 */
public class RestCatalogClient implements CatalogClient {

    // (2) Bản "copy tay" của ProductResponse phía server. Không có gì đảm bảo 2 bản khớp nhau.
    record ProductJson(String sku, String name, long price, int stock) {
    }

    // (3) Bản "copy tay" của ErrorBody phía server.
    record ErrorJson(String code, String message) {
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RestCatalogClient(String baseUrl, Duration timeout, ObjectMapper objectMapper) {
        // Timeout của REST cấu hình 1 lần ở tầng HTTP client (connect + read), áp cho mọi request.
        // THIẾU dòng này thì mặc định là chờ VÔ HẠN - lỗi kinh điển làm treo cả chuỗi service.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);

        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ProductQuote> getProducts(List<String> skus) {
        try {
            ProductJson[] body = restClient.get()
                    // (1) URL là chuỗi - compiler không kiểm tra được.
                    .uri(uri -> uri.path("/api/v1/products").queryParam("skus", String.join(",", skus)).build())
                    .retrieve()
                    // (3) Dịch HTTP status -> exception nghiệp vụ, tự parse body lỗi.
                    .onStatus(status -> status.isSameCodeAs(HttpStatus.NOT_FOUND), (request, response) -> {
                        ErrorJson error = objectMapper.readValue(response.getBody(), ErrorJson.class);
                        throw new ProductNotFoundException(error.message());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                        throw new CatalogUnavailableException("Catalog trả HTTP " + response.getStatusCode(), null);
                    })
                    .body(ProductJson[].class);

            return body == null ? List.of() : Arrays.stream(body)
                    .map(p -> new ProductQuote(p.sku(), p.name(), p.price(), p.stock()))
                    .toList();
        } catch (ResourceAccessException e) {
            // Lỗi I/O: read timeout, connection refused... RestClient gói chung vào 1 loại
            // exception, muốn phân biệt chi tiết phải soi e.getCause().
            throw new CatalogUnavailableException("REST: không nhận được phản hồi từ Catalog - " + e.getMessage(), e);
        }
    }
}
