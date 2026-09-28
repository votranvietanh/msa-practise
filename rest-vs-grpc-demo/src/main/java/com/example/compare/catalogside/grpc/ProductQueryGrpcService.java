package com.example.compare.catalogside.grpc;

import com.example.compare.catalogside.ProductCatalog;
import com.example.compare.catalogside.ProductInfo;
import com.example.compare.catalogside.UnknownSkuException;
import com.example.compare.grpc.GetProductsRequest;
import com.example.compare.grpc.GetProductsResponse;
import com.example.compare.grpc.Product;
import com.example.compare.grpc.ProductQueryServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

/**
 * "Cửa gRPC" của Catalog Service - làm ĐÚNG việc giống CatalogRestController, khác ở:
 *   - Không có URL/HTTP method: tên method (getProducts) chính là "endpoint".
 *   - Không có DTO viết tay: Product/GetProductsRequest/... sinh tự động từ .proto.
 *   - Kết quả trả qua responseObserver.onNext()+onCompleted() thay vì `return`.
 *   - Lỗi trả qua onError(Status.XXX) thay vì @ExceptionHandler + HTTP status.
 *
 * Thử tay: grpcurl -plaintext -d '{"skus":["SHIRT-01","JEAN-01"]}' localhost:9095 productquery.v1.ProductQueryService/GetProducts
 */
@Component
public class ProductQueryGrpcService extends ProductQueryServiceGrpc.ProductQueryServiceImplBase {

    private final ProductCatalog catalog;

    public ProductQueryGrpcService(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public void getProducts(GetProductsRequest request, StreamObserver<GetProductsResponse> responseObserver) {
        try {
            GetProductsResponse.Builder response = GetProductsResponse.newBuilder();
            for (ProductInfo p : catalog.findAll(request.getSkusList())) {
                response.addProducts(Product.newBuilder()
                        .setSku(p.sku())
                        .setName(p.name())
                        .setPrice(p.price())
                        .setStock(p.stock())
                        .build());
            }
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (UnknownSkuException e) {
            // Tương đương HTTP 404, nhưng là status code CHUẨN của gRPC - mọi client gRPC
            // ở mọi ngôn ngữ đều hiểu giống nhau, không cần quy ước format body lỗi riêng.
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
