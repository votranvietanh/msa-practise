package com.example.catalog.grpc;

import com.example.catalog.entity.Product;
import com.example.catalog.redis.LeaderboardService;
import com.example.catalog.redis.ProductEventPublisher;
import com.example.catalog.redis.RedisProductWatchBridge;
import com.example.catalog.redis.StockService;
import com.example.catalog.redis.TopSellerEntry;
import com.example.catalog.repository.ProductRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * File này nằm CÙNG PACKAGE (com.example.catalog.grpc) với code do protobuf-maven-plugin
 * sinh ra từ catalog.proto (CatalogServiceGrpc, ProductDto, CreateProductRequest...) - vì
 * vậy không cần import chúng, dù chúng nằm ở target/generated-sources/ chứ không phải
 * src/main/java/. Chạy `mvn compile` ít nhất 1 lần để IDE nhận ra các class này.
 *
 * extends CatalogServiceGrpc.CatalogServiceImplBase: lớp base do plugin sinh sẵn, có đủ
 * 6 method (khớp 6 rpc khai báo trong catalog.proto) với implementation mặc định là trả
 * lỗi UNIMPLEMENTED - override từng method bên dưới để cung cấp logic thật.
 */
@Component
public class CatalogGrpcService extends CatalogServiceGrpc.CatalogServiceImplBase {

    private final ProductRepository productRepository;
    private final StockService stockService;
    private final LeaderboardService leaderboardService;
    private final ProductEventPublisher eventPublisher;
    private final RedisProductWatchBridge watchBridge;

    public CatalogGrpcService(ProductRepository productRepository,
                               StockService stockService,
                               LeaderboardService leaderboardService,
                               ProductEventPublisher eventPublisher,
                               RedisProductWatchBridge watchBridge) {
        this.productRepository = productRepository;
        this.stockService = stockService;
        this.leaderboardService = leaderboardService;
        this.eventPublisher = eventPublisher;
        this.watchBridge = watchBridge;
    }

    // =========================================================================
    // UNARY RPC — mỗi method nhận đúng 1 request, PHẢI gọi đúng 1 trong 2:
    // onNext()+onCompleted() (thành công) HOẶC onError() (thất bại) - gọi cả 2, gọi thiếu,
    // hay gọi onNext() nhiều lần đều là lỗi (client sẽ nhận UNKNOWN hoặc treo mãi).
    // =========================================================================

    @Override
    public void createProduct(CreateProductRequest request, StreamObserver<ProductDto> responseObserver) {
        String sku = request.getSku();

        if (sku.isBlank()) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("sku không được để trống").asRuntimeException());
            return;
        }
        if (productRepository.existsBySku(sku)) {
            responseObserver.onError(Status.ALREADY_EXISTS
                    .withDescription("Sản phẩm " + sku + " đã tồn tại").asRuntimeException());
            return;
        }

        Product product = productRepository.save(new Product(sku, request.getName(), request.getPrice()));
        stockService.initStock(sku, request.getInitialStock());

        responseObserver.onNext(toDto(product));
        responseObserver.onCompleted();
    }

    @Override
    public void getProduct(GetProductRequest request, StreamObserver<ProductDto> responseObserver) {
        Optional<Product> product = productRepository.findBySku(request.getSku());

        if (product.isEmpty()) {
            responseObserver.onError(notFound(request.getSku()));
            return;
        }

        responseObserver.onNext(toDto(product.get()));
        responseObserver.onCompleted();
    }

    @Override
    public void checkStock(CheckStockRequest request, StreamObserver<CheckStockResponse> responseObserver) {
        if (!productRepository.existsBySku(request.getSku())) {
            responseObserver.onError(notFound(request.getSku()));
            return;
        }

        responseObserver.onNext(CheckStockResponse.newBuilder()
                .setSku(request.getSku())
                .setQuantity(stockService.getStock(request.getSku()))
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void getTopSellers(GetTopSellersRequest request, StreamObserver<TopSellersResponse> responseObserver) {
        int limit = request.getLimit() > 0 ? request.getLimit() : 10;

        TopSellersResponse.Builder builder = TopSellersResponse.newBuilder();
        for (TopSellerEntry entry : leaderboardService.topSellers(limit)) {
            builder.addItems(TopSeller.newBuilder()
                    .setSku(entry.sku())
                    .setSoldCount(entry.soldCount())
                    .build());
        }

        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    // =========================================================================
    // SERVER STREAMING RPC — cầu nối Redis Pub/Sub -> gRPC stream. 2 điều BẮT BUỘC
    // phải làm đúng, cả 2 đều là lỗi kinh điển khi mới học server-streaming:
    //
    //  1) Dọn dẹp subscription khi client huỷ kết nối (setOnCancelHandler) - thiếu bước
    //     này, mỗi client disconnect mà không dọn sẽ rò rỉ thêm 1 MessageListener treo
    //     mãi mãi trong RedisMessageListenerContainer (memory leak + tốn CPU vô ích).
    //  2) responseObserver KHÔNG thread-safe - onNext() ở đây bị gọi từ thread của Redis
    //     listener (khác thread gRPC gốc xử lý request), nên phải synchronized nếu có khả
    //     năng nhiều thread cùng ghi vào 1 observer.
    // =========================================================================

    @Override
    public void watchProduct(WatchProductRequest request, StreamObserver<ProductUpdate> responseObserver) {
        String sku = request.getSku();

        if (!productRepository.existsBySku(sku)) {
            responseObserver.onError(notFound(sku));
            return;
        }

        ServerCallStreamObserver<ProductUpdate> serverObserver =
                (ServerCallStreamObserver<ProductUpdate>) responseObserver;

        RedisProductWatchBridge.Subscription subscription = watchBridge.watch(sku, payload -> {
            synchronized (responseObserver) {
                if (!serverObserver.isCancelled()) {
                    responseObserver.onNext(ProductUpdate.newBuilder()
                            .setSku(payload.sku())
                            .setType(toUpdateType(payload.type()))
                            .setNewQuantity(payload.newQuantity())
                            .setMessage(payload.message())
                            .build());
                }
            }
        });

        serverObserver.setOnCancelHandler(subscription::cancel);
    }

    // =========================================================================
    // CLIENT STREAMING RPC — kiểu streaming DUY NHẤT có signature Java "ngược" trực giác:
    // method TRẢ VỀ 1 StreamObserver<RestockItem> thay vì nhận request làm tham số - đó
    // chính là "cổng vào" mà gRPC framework gọi onNext() mỗi lần client gửi 1 RestockItem,
    // và gọi onCompleted() khi client báo đã gửi xong. responseObserver (BulkRestockSummary)
    // chỉ dùng đúng 1 lần, ở onCompleted().
    // =========================================================================

    @Override
    public StreamObserver<RestockItem> bulkRestock(StreamObserver<BulkRestockSummary> responseObserver) {
        return new StreamObserver<>() {

            private int itemsProcessed = 0;
            private long totalQtyAdded = 0;
            private final List<String> errors = new ArrayList<>();

            @Override
            public void onNext(RestockItem item) {
                if (!productRepository.existsBySku(item.getSku())) {
                    errors.add("Bỏ qua " + item.getSku() + ": sản phẩm không tồn tại");
                    return;
                }
                if (item.getQty() <= 0) {
                    errors.add("Bỏ qua " + item.getSku() + ": qty phải > 0");
                    return;
                }

                long newStock = stockService.restock(item.getSku(), item.getQty());
                itemsProcessed++;
                totalQtyAdded += item.getQty();

                eventPublisher.publish(item.getSku(), "RESTOCKED", newStock,
                        "Nhập thêm " + item.getQty() + " qua BulkRestock");
            }

            @Override
            public void onError(Throwable t) {
                // Client gặp lỗi giữa chừng (vd mất mạng) - log lại, KHÔNG gọi onNext/
                // onCompleted trên responseObserver nữa vì phía client cũng không còn lắng nghe.
                System.err.println("[BulkRestock] Client gửi lỗi giữa chừng: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                responseObserver.onNext(BulkRestockSummary.newBuilder()
                        .setItemsProcessed(itemsProcessed)
                        .setTotalQtyAdded(totalQtyAdded)
                        .addAllErrors(errors)
                        .build());
                responseObserver.onCompleted();
            }
        };
    }

    // =========================================================================
    // BIDIRECTIONAL STREAMING RPC — khác BulkRestock ở chỗ: mỗi ReserveRequest nhận được
    // trả lời NGAY qua responseObserver.onNext() (không đợi client onCompleted() mới gộp
    // trả lời 1 lần) - đây là điểm cốt lõi của bidi-streaming: request và response chạy
    // trên 2 luồng độc lập, không bị ép khớp thứ tự chặt như request/response REST.
    // =========================================================================

    @Override
    public StreamObserver<ReserveRequest> reserveStockSession(StreamObserver<ReserveResponse> responseObserver) {
        return new StreamObserver<>() {

            @Override
            public void onNext(ReserveRequest request) {
                long remaining = stockService.tryReserve(request.getSku(), request.getQty());
                boolean approved = remaining >= 0;

                if (approved) {
                    leaderboardService.recordSale(request.getSku(), request.getQty());
                    eventPublisher.publish(request.getSku(), "RESERVED", remaining,
                            "Order " + request.getOrderId() + " giữ " + request.getQty() + " sản phẩm");
                }

                responseObserver.onNext(ReserveResponse.newBuilder()
                        .setRequestId(request.getRequestId())
                        .setApproved(approved)
                        .setRemainingStock(approved ? remaining : stockService.getStock(request.getSku()))
                        .setMessage(approved ? "OK" : "Không đủ hàng")
                        .build());
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("[ReserveStockSession] Client gửi lỗi giữa chừng: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private ProductDto toDto(Product product) {
        return ProductDto.newBuilder()
                .setSku(product.getSku())
                .setName(product.getName())
                .setPrice(product.getPrice())
                .build();
    }

    /** NOT_FOUND: status code chuẩn gRPC cho "không tìm thấy" (tương đương HTTP 404). */
    private StatusRuntimeException notFound(String sku) {
        return Status.NOT_FOUND.withDescription("Không tìm thấy sản phẩm " + sku).asRuntimeException();
    }

    private ProductUpdate.UpdateType toUpdateType(String type) {
        try {
            return ProductUpdate.UpdateType.valueOf(type);
        } catch (IllegalArgumentException e) {
            return ProductUpdate.UpdateType.UNKNOWN;
        }
    }
}
