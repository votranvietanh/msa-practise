package com.example.compare;

import com.example.compare.catalogside.ProductCatalog;
import com.example.compare.catalogside.grpc.GrpcServerLifecycle;
import com.example.compare.catalogside.rest.ProductResponse;
import com.example.compare.grpc.GetProductsResponse;
import com.example.compare.grpc.Product;
import com.example.compare.orderside.CatalogClient;
import com.example.compare.orderside.CatalogUnavailableException;
import com.example.compare.orderside.OrderQuote;
import com.example.compare.orderside.OrderQuoteService;
import com.example.compare.orderside.OutOfStockException;
import com.example.compare.orderside.ProductNotFoundException;
import com.example.compare.orderside.grpc.GrpcCatalogClient;
import com.example.compare.orderside.rest.RestCatalogClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Chạy CÙNG 1 kịch bản nghiệp vụ qua 2 giao thức, qua mạng thật (localhost). Mỗi test
 * @ParameterizedTest chạy 2 lần: 1 lần "REST", 1 lần "gRPC" - cùng assertion, cùng kết quả.
 *
 * Chạy: mvn test   (đọc các dòng [SO SÁNH] in ra console)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "grpc.server.port=0")
class RestVsGrpcComparisonTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @LocalServerPort
    int restPort;

    @Autowired
    GrpcServerLifecycle grpcServer;

    @Autowired
    ObjectMapper objectMapper;

    private ManagedChannel channel;
    private OrderQuoteService viaRest;
    private OrderQuoteService viaGrpc;

    @BeforeEach
    void setUp() {
        // Port random (tránh đụng app đang chạy) nên tự dựng client ở đây thay vì dùng bean trong CatalogClientConfig.
        channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort()).usePlaintext().build();

        CatalogClient restClient = new RestCatalogClient("http://localhost:" + restPort, TIMEOUT, objectMapper);
        CatalogClient grpcClient = new GrpcCatalogClient(channel, TIMEOUT);

        viaRest = new OrderQuoteService(restClient);
        viaGrpc = new OrderQuoteService(grpcClient);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
    }

    private OrderQuoteService service(String protocol) {
        return protocol.equals("REST") ? viaRest : viaGrpc;
    }

    private static Map<String, Integer> items(Object... skuQtyPairs) {
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < skuQtyPairs.length; i += 2) {
            items.put((String) skuQtyPairs[i], (Integer) skuQtyPairs[i + 1]);
        }
        return items;
    }

    // ---- Kịch bản 1: thành công -> 2 giao thức cho kết quả Y HỆT nhau ----
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"REST", "gRPC"})
    void happyPath_sameResultOnBothProtocols(String protocol) {
        OrderQuote quote = service(protocol).quote(items("SHIRT-01", 2, "JEAN-01", 1));

        assertThat(quote.total()).isEqualTo(2 * 150_000 + 450_000);
        assertThat(quote.lines()).extracting(OrderQuote.Line::sku).containsExactly("SHIRT-01", "JEAN-01");
        System.out.println("[SO SÁNH] " + protocol + " happy path -> " + quote);
    }

    // ---- Kịch bản 2: SKU không tồn tại -> REST 404 / gRPC NOT_FOUND, nhưng client đều dịch
    // về CÙNG 1 exception nghiệp vụ nên code phía trên không phân biệt được ----
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"REST", "gRPC"})
    void unknownSku_mappedToSameBusinessException(String protocol) {
        assertThatThrownBy(() -> service(protocol).quote(items("SHIRT-01", 1, "GHOST-99", 1)))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("GHOST-99");
    }

    // ---- Kịch bản 3: lỗi nghiệp vụ phía ORDER (hết hàng) - không liên quan giao thức ----
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"REST", "gRPC"})
    void outOfStock_isPureBusinessLogic(String protocol) {
        assertThatThrownBy(() -> service(protocol).quote(items("CAP-01", 1)))
                .isInstanceOf(OutOfStockException.class);
    }

    // ---- Kịch bản 4: Catalog chậm 2s, client chỉ chờ 1s ----
    // REST: read timeout ở tầng socket -> ResourceAccessException.
    // gRPC: deadline -> DEADLINE_EXCEEDED (và server CŨNG được báo deadline qua header grpc-timeout).
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"REST", "gRPC"})
    void slowCatalog_clientGivesUpAfterTimeout(String protocol) {
        long start = System.nanoTime();

        assertThatThrownBy(() -> service(protocol).quote(items(ProductCatalog.SLOW_SKU, 1)))
                .isInstanceOf(CatalogUnavailableException.class)
                .satisfies(e -> System.out.println("[SO SÁNH] " + protocol + " timeout -> " + e.getMessage()));

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(1_900); // bỏ cuộc ở ~1s, không đợi đủ 2s của server
    }

    // ---- Đo kích thước payload: CÙNG 1 dữ liệu, JSON vs Protobuf ----
    @Test
    void payloadSize_protobufIsSmallerThanJson() throws Exception {
        List<ProductResponse> jsonDtos = new ArrayList<>();
        GetProductsResponse.Builder proto = GetProductsResponse.newBuilder();
        for (int i = 1; i <= 100; i++) {
            String sku = "SKU-%05d".formatted(i);
            jsonDtos.add(new ProductResponse(sku, "Sản phẩm số " + i, 150_000L + i, 20 + i));
            proto.addProducts(Product.newBuilder()
                    .setSku(sku).setName("Sản phẩm số " + i).setPrice(150_000L + i).setStock(20 + i).build());
        }

        int jsonBytes = objectMapper.writeValueAsBytes(jsonDtos).length;
        int protoBytes = proto.build().toByteArray().length;

        System.out.printf("[SO SÁNH] 100 sản phẩm: JSON = %d bytes, Protobuf = %d bytes (nhỏ hơn %.0f%%)%n",
                jsonBytes, protoBytes, 100.0 * (jsonBytes - protoBytes) / jsonBytes);
        assertThat(protoBytes).isLessThan(jsonBytes);
    }
}
