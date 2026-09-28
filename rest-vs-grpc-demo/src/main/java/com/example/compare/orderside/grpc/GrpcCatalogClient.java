package com.example.compare.orderside.grpc;

import com.example.compare.grpc.GetProductsRequest;
import com.example.compare.grpc.GetProductsResponse;
import com.example.compare.grpc.ProductQueryServiceGrpc;
import com.example.compare.orderside.CatalogClient;
import com.example.compare.orderside.CatalogUnavailableException;
import com.example.compare.orderside.ProductNotFoundException;
import com.example.compare.orderside.ProductQuote;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Gọi Catalog Service qua gRPC. So với RestCatalogClient, để ý những thứ KHÔNG còn phải làm:
 *   (1) Không có URL: gọi method stub.getProducts(...) - gõ sai tên là lỗi compile ngay.
 *   (2) Không có DTO viết tay: GetProductsRequest/Product sinh từ .proto, dùng chung với server.
 *   (3) Không parse body lỗi: chỉ cần switch trên Status.Code chuẩn.
 */
public class GrpcCatalogClient implements CatalogClient {

    private final ProductQueryServiceGrpc.ProductQueryServiceBlockingStub stub;
    private final Duration timeout;

    public GrpcCatalogClient(Channel channel, Duration timeout) {
        this.stub = ProductQueryServiceGrpc.newBlockingStub(channel);
        this.timeout = timeout;
    }

    @Override
    public List<ProductQuote> getProducts(List<String> skus) {
        try {
            GetProductsResponse response = stub
                    // Deadline là MỐC THỜI GIAN TUYỆT ĐỐI, nên phải gắn MỖI LẦN GỌI - nếu gắn 1 lần
                    // lúc tạo stub trong constructor, sau `timeout` giây mọi lời gọi đều hết hạn ngay.
                    // Deadline còn được TRUYỀN SANG SERVER (header grpc-timeout): server biết client
                    // chỉ đợi bao lâu và có thể tự dừng sớm - REST không có cơ chế chuẩn tương đương.
                    .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    // (1) + (2): gọi như method Java, request là object có kiểu rõ ràng.
                    .getProducts(GetProductsRequest.newBuilder().addAllSkus(skus).build());

            return response.getProductsList().stream()
                    .map(p -> new ProductQuote(p.getSku(), p.getName(), p.getPrice(), p.getStock()))
                    .toList();
        } catch (StatusRuntimeException e) {
            // (3) Mọi lỗi gRPC đều là StatusRuntimeException mang 1 Status.Code chuẩn.
            switch (e.getStatus().getCode()) {
                case NOT_FOUND -> throw new ProductNotFoundException(e.getStatus().getDescription());
                case DEADLINE_EXCEEDED, UNAVAILABLE ->
                        throw new CatalogUnavailableException("gRPC: " + e.getStatus().getCode() + " - " + e.getMessage(), e);
                default -> throw e;
            }
        }
    }
}
