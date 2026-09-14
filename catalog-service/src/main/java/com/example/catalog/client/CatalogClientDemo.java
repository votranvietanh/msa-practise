package com.example.catalog.client;

import com.example.catalog.grpc.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * KHÔNG phải bean Spring, không tự chạy chung với CatalogServiceApplication - đây là 1
 * chương trình Java THUẦN TUÝ, độc lập, đóng vai "client thật" gọi qua mạng (khác hẳn
 * CatalogGrpcServiceTest dùng in-process server). Chạy để xem cả 4 kiểu RPC hoạt động
 * ra sao từ góc nhìn CLIENT - phần code các bài hướng dẫn gRPC hay lược bỏ.
 *
 * Cách chạy:
 *   1. Bật Redis + catalog-service thật (xem README.md mục 13).
 *   2. Chạy class này: mvn -q exec:java -Dexec.mainClass=com.example.catalog.client.CatalogClientDemo
 *      (hoặc chạy thẳng main() từ IDE).
 */
public class CatalogClientDemo {

    public static void main(String[] args) throws InterruptedException {
        // ManagedChannelBuilder.forAddress(host, port): channel gRPC THẬT, kết nối qua
        // mạng (khác InProcessChannelBuilder dùng trong test). usePlaintext(): tắt TLS -
        // CHỈ chấp nhận được khi test cục bộ, production luôn phải bật TLS cho gRPC.
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 9090)
                .usePlaintext()
                .build();

        try {
            CatalogServiceGrpc.CatalogServiceBlockingStub blockingStub = CatalogServiceGrpc.newBlockingStub(channel);
            CatalogServiceGrpc.CatalogServiceStub asyncStub = CatalogServiceGrpc.newStub(channel);

            unaryDemo(blockingStub);
            clientStreamingDemo(asyncStub);
            bidiStreamingDemo(asyncStub);
            serverStreamingDemo(asyncStub, blockingStub);
        } finally {
            // shutdownNow() ở demo cho gọn - code thật nên dùng shutdown() + awaitTermination()
            // để các request đang bay dở có cơ hội hoàn tất trước khi đóng hẳn.
            channel.shutdownNow();
        }
    }

    // ---- 1) UNARY: gọi y hệt gọi 1 method Java bình thường, blockingStub tự đợi response ----
    private static void unaryDemo(CatalogServiceGrpc.CatalogServiceBlockingStub stub) {
        System.out.println("\n=== 1) UNARY: CreateProduct + GetProduct + CheckStock ===");

        ProductDto created = stub.createProduct(CreateProductRequest.newBuilder()
                .setSku("DEMO-01").setName("Ao thun Demo").setPrice(150_000).setInitialStock(20)
                .build());
        System.out.println("Đã tạo: " + created.getSku() + " - " + created.getName());

        CheckStockResponse stock = stub.checkStock(CheckStockRequest.newBuilder().setSku("DEMO-01").build());
        System.out.println("Tồn kho hiện tại: " + stock.getQuantity());
    }

    // ---- 2) CLIENT STREAMING: gửi nhiều request, chỉ nhận 1 response cuối cùng ----
    private static void clientStreamingDemo(CatalogServiceGrpc.CatalogServiceStub stub) throws InterruptedException {
        System.out.println("\n=== 2) CLIENT STREAMING: BulkRestock ===");

        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<RestockItem> requestObserver = stub.bulkRestock(new StreamObserver<>() {
            @Override
            public void onNext(BulkRestockSummary summary) {
                System.out.println("Kết quả nhập kho: " + summary.getItemsProcessed()
                        + " sản phẩm, tổng +" + summary.getTotalQtyAdded() + " - lỗi: " + summary.getErrorsList());
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("BulkRestock lỗi: " + t.getMessage());
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        });

        // Giả lập đọc từng dòng từ 1 file CSV nhập kho, gửi từng message 1 (không phải gói
        // hết vào 1 request khổng lồ) - đây chính là lý do dùng client streaming.
        for (int i = 1; i <= 3; i++) {
            requestObserver.onNext(RestockItem.newBuilder().setSku("DEMO-01").setQty(10).build());
            Thread.sleep(100); // giả lập độ trễ đọc file, không bắt buộc về mặt kỹ thuật
        }
        requestObserver.onCompleted(); // BẮT BUỘC gọi, nếu không server chờ mãi không bao giờ trả response

        done.await(5, TimeUnit.SECONDS);
    }

    // ---- 3) BIDI STREAMING: gửi và nhận đan xen, không cần đợi onCompleted ----
    private static void bidiStreamingDemo(CatalogServiceGrpc.CatalogServiceStub stub) throws InterruptedException {
        System.out.println("\n=== 3) BIDIRECTIONAL STREAMING: ReserveStockSession ===");

        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<ReserveRequest> requestObserver = stub.reserveStockSession(new StreamObserver<>() {
            @Override
            public void onNext(ReserveResponse response) {
                // Response này có thể tới BẤT CỨ LÚC NÀO, kể cả trước khi client gửi xong
                // hết các ReserveRequest còn lại - đây là điểm khác biệt cốt lõi so với
                // client streaming ở trên (nơi PHẢI đợi onCompleted mới có response).
                System.out.println("Phản hồi cho " + response.getRequestId() + ": "
                        + (response.getApproved() ? "DUYỆT, còn lại " + response.getRemainingStock() : "TỪ CHỐI - " + response.getMessage()));
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("ReserveStockSession lỗi: " + t.getMessage());
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        });

        for (int i = 1; i <= 3; i++) {
            requestObserver.onNext(ReserveRequest.newBuilder()
                    .setRequestId("req-" + i).setOrderId("ORD-DEMO").setSku("DEMO-01").setQty(5)
                    .build());
        }
        requestObserver.onCompleted();

        done.await(5, TimeUnit.SECONDS);
    }

    // ---- 4) SERVER STREAMING: 1 request, nhận nhiều response đẩy về theo thời gian ----
    private static void serverStreamingDemo(CatalogServiceGrpc.CatalogServiceStub asyncStub,
                                             CatalogServiceGrpc.CatalogServiceBlockingStub blockingStub) throws InterruptedException {
        System.out.println("\n=== 4) SERVER STREAMING: WatchProduct (lắng nghe 3 giây) ===");

        CountDownLatch watching = new CountDownLatch(1);
        asyncStub.watchProduct(WatchProductRequest.newBuilder().setSku("DEMO-01").build(), new StreamObserver<>() {
            @Override
            public void onNext(ProductUpdate update) {
                System.out.println("[watch] " + update.getSku() + " " + update.getType()
                        + " -> tồn kho mới: " + update.getNewQuantity() + " (" + update.getMessage() + ")");
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("WatchProduct lỗi: " + t.getMessage());
                watching.countDown();
            }

            @Override
            public void onCompleted() {
                watching.countDown();
            }
        });

        // Trong lúc đang "watch", gọi thêm vài thao tác để tạo ra update thật (BulkRestock/
        // ReserveStockSession ở trên đã publish update rồi - gọi thêm để thấy rõ hơn).
        blockingStub.checkStock(CheckStockRequest.newBuilder().setSku("DEMO-01").build());

        // Demo chỉ lắng nghe trong 3 giây rồi tự huỷ - stream server-streaming không tự kết
        // thúc, client phải chủ động huỷ (hoặc đóng channel) khi không cần nữa.
        watching.await(3, TimeUnit.SECONDS);
        System.out.println("(Kết thúc demo watch - trong thực tế client có thể giữ kết nối này vô thời hạn)");
    }
}
