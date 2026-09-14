package com.example.catalog.grpc;

import com.example.catalog.redis.LeaderboardService;
import com.example.catalog.redis.ProductEventPublisher;
import com.example.catalog.redis.RedisProductWatchBridge;
import com.example.catalog.redis.StockService;
import com.example.catalog.redis.TopSellerEntry;
import com.example.catalog.repository.ProductRepository;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Test gRPC service KHÔNG mở cổng TCP thật - dùng InProcessServerBuilder/
 * InProcessChannelBuilder (gRPC chạy hoàn toàn trong 1 JVM process, qua tên định danh
 * thay vì host:port). Nhanh hơn nhiều so với mở port thật, và nhiều test chạy song song
 * không giẫm chân nhau vì không tranh chấp port.
 *
 * .directExecutor(): mọi callback (onNext/onError/onCompleted) chạy TRÊN CHÍNH thread gọi
 * request, thay vì thread pool riêng của gRPC - giúp test đơn giản, không cần đợi bất đồng
 * bộ (không có threading không xác định), miễn logic bên trong service không tự blocking.
 */
@ExtendWith(MockitoExtension.class)
class CatalogGrpcServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private StockService stockService;
    @Mock private LeaderboardService leaderboardService;
    @Mock private ProductEventPublisher eventPublisher;
    @Mock private RedisProductWatchBridge watchBridge;

    private Server server;
    private ManagedChannel channel;
    private CatalogServiceGrpc.CatalogServiceBlockingStub blockingStub;
    private CatalogServiceGrpc.CatalogServiceStub asyncStub;

    @BeforeEach
    void setUp() throws Exception {
        CatalogGrpcService service = new CatalogGrpcService(
                productRepository, stockService, leaderboardService, eventPublisher, watchBridge);

        String serverName = "in-process-" + System.nanoTime();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(service)
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

        blockingStub = CatalogServiceGrpc.newBlockingStub(channel);
        asyncStub = CatalogServiceGrpc.newStub(channel);
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    // ==================== UNARY ====================

    @Test
    void createProduct_thanhCong_traVeProductDto_vaInitStock() {
        when(productRepository.existsBySku("SKU-1")).thenReturn(false);
        when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductDto response = blockingStub.createProduct(CreateProductRequest.newBuilder()
                .setSku("SKU-1").setName("Ao thun").setPrice(100_000).setInitialStock(50)
                .build());

        assertThat(response.getSku()).isEqualTo("SKU-1");
        assertThat(response.getName()).isEqualTo("Ao thun");
        verify(stockService).initStock("SKU-1", 50);
    }

    @Test
    void createProduct_daTonTai_traVeAlreadyExists() {
        when(productRepository.existsBySku("SKU-2")).thenReturn(true);

        StatusRuntimeException ex = catchGrpcException(() ->
                blockingStub.createProduct(CreateProductRequest.newBuilder().setSku("SKU-2").build()));

        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
        verify(productRepository, never()).save(any());
    }

    @Test
    void createProduct_skuRong_traVeInvalidArgument() {
        StatusRuntimeException ex = catchGrpcException(() ->
                blockingStub.createProduct(CreateProductRequest.newBuilder().setSku("").build()));

        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    @Test
    void getProduct_khongTonTai_traVeNotFound() {
        when(productRepository.findBySku("SKU-X")).thenReturn(Optional.empty());

        StatusRuntimeException ex = catchGrpcException(() ->
                blockingStub.getProduct(GetProductRequest.newBuilder().setSku("SKU-X").build()));

        assertThat(ex.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
    }

    @Test
    void checkStock_traVeSoLuongTuStockService() {
        when(productRepository.existsBySku("SKU-3")).thenReturn(true);
        when(stockService.getStock("SKU-3")).thenReturn(42L);

        CheckStockResponse response = blockingStub.checkStock(
                CheckStockRequest.newBuilder().setSku("SKU-3").build());

        assertThat(response.getQuantity()).isEqualTo(42L);
    }

    @Test
    void getTopSellers_traVeDanhSachTuLeaderboard() {
        when(leaderboardService.topSellers(10)).thenReturn(List.of(new TopSellerEntry("SKU-HOT", 99)));

        TopSellersResponse response = blockingStub.getTopSellers(
                GetTopSellersRequest.newBuilder().setLimit(10).build());

        assertThat(response.getItemsList()).hasSize(1);
        assertThat(response.getItems(0).getSku()).isEqualTo("SKU-HOT");
        assertThat(response.getItems(0).getSoldCount()).isEqualTo(99);
    }

    // ==================== SERVER STREAMING ====================

    @Test
    void watchProduct_dangKySubscriptionDungSku() {
        when(productRepository.existsBySku("SKU-W")).thenReturn(true);
        RedisProductWatchBridge.Subscription subscription = mock(RedisProductWatchBridge.Subscription.class);
        when(watchBridge.watch(eq("SKU-W"), any())).thenReturn(subscription);

        // watchProduct KHÔNG tự kết thúc (server streaming mở vô thời hạn tới khi có update
        // hoặc client huỷ) - test này chỉ verify hành vi ĐĂNG KÝ đúng sku, không đợi data.
        asyncStub.watchProduct(WatchProductRequest.newBuilder().setSku("SKU-W").build(),
                new StreamObserver<>() {
                    @Override public void onNext(ProductUpdate value) {}
                    @Override public void onError(Throwable t) {}
                    @Override public void onCompleted() {}
                });

        verify(watchBridge).watch(eq("SKU-W"), any());
    }

    // ==================== CLIENT STREAMING ====================

    @Test
    void bulkRestock_gomNhieuItem_traVeSummaryDung() throws InterruptedException {
        when(productRepository.existsBySku(anyString())).thenReturn(true);
        when(stockService.restock("SKU-A", 10L)).thenReturn(60L);
        when(stockService.restock("SKU-B", 5L)).thenReturn(25L);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BulkRestockSummary> resultRef = new AtomicReference<>();

        StreamObserver<RestockItem> requestObserver = asyncStub.bulkRestock(new StreamObserver<>() {
            @Override public void onNext(BulkRestockSummary value) { resultRef.set(value); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });

        requestObserver.onNext(RestockItem.newBuilder().setSku("SKU-A").setQty(10).build());
        requestObserver.onNext(RestockItem.newBuilder().setSku("SKU-B").setQty(5).build());
        requestObserver.onCompleted();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(resultRef.get().getItemsProcessed()).isEqualTo(2);
        assertThat(resultRef.get().getTotalQtyAdded()).isEqualTo(15);
        assertThat(resultRef.get().getErrorsList()).isEmpty();
    }

    @Test
    void bulkRestock_boQuaItemKhongTonTai_vaGhiVaoErrors() throws InterruptedException {
        when(productRepository.existsBySku("SKU-C")).thenReturn(true);
        when(productRepository.existsBySku("SKU-KHONG-CO")).thenReturn(false);
        when(stockService.restock("SKU-C", 4L)).thenReturn(4L);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BulkRestockSummary> resultRef = new AtomicReference<>();

        StreamObserver<RestockItem> requestObserver = asyncStub.bulkRestock(new StreamObserver<>() {
            @Override public void onNext(BulkRestockSummary value) { resultRef.set(value); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });

        requestObserver.onNext(RestockItem.newBuilder().setSku("SKU-KHONG-CO").setQty(1).build());
        requestObserver.onNext(RestockItem.newBuilder().setSku("SKU-C").setQty(4).build());
        requestObserver.onCompleted();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(resultRef.get().getItemsProcessed()).isEqualTo(1);
        assertThat(resultRef.get().getErrorsList()).hasSize(1);
    }

    // ==================== BIDI STREAMING ====================

    @Test
    void reserveStockSession_duyetVaTuChoiTheoTonKho() throws InterruptedException {
        when(stockService.tryReserve("SKU-D", 3L)).thenReturn(7L);    // đủ hàng
        when(stockService.tryReserve("SKU-D", 100L)).thenReturn(-1L); // hết hàng
        when(stockService.getStock("SKU-D")).thenReturn(7L);

        CountDownLatch latch = new CountDownLatch(1);
        List<ReserveResponse> responses = new CopyOnWriteArrayList<>();

        StreamObserver<ReserveRequest> requestObserver = asyncStub.reserveStockSession(new StreamObserver<>() {
            @Override public void onNext(ReserveResponse value) { responses.add(value); }
            @Override public void onError(Throwable t) { latch.countDown(); }
            @Override public void onCompleted() { latch.countDown(); }
        });

        requestObserver.onNext(ReserveRequest.newBuilder().setRequestId("r1").setSku("SKU-D").setQty(3).build());
        requestObserver.onNext(ReserveRequest.newBuilder().setRequestId("r2").setSku("SKU-D").setQty(100).build());
        requestObserver.onCompleted();

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getApproved()).isTrue();
        assertThat(responses.get(0).getRemainingStock()).isEqualTo(7L);
        assertThat(responses.get(1).getApproved()).isFalse();

        verify(leaderboardService).recordSale("SKU-D", 3L);
        verify(leaderboardService, never()).recordSale("SKU-D", 100L);
    }

    private StatusRuntimeException catchGrpcException(Runnable call) {
        try {
            call.run();
        } catch (StatusRuntimeException e) {
            return e;
        }
        throw new AssertionError("Kỳ vọng StatusRuntimeException nhưng không có exception nào được ném ra");
    }
}
