package com.example.catalog.client;

import com.example.catalog.grpc.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * KHÔNG phải bean Spring, không tự chạy chung với CatalogServiceApplication - đây là 1
 * chương trình Java THUẦN TUÝ, độc lập, đóng vai "client thật" gọi qua mạng (khác hẳn
 * CatalogGrpcServiceTest dùng in-process server). Chạy để xem cả 4 kiểu RPC hoạt động
 * ra sao từ góc nhìn CLIENT - phần code các bài hướng dẫn gRPC hay lược bỏ.
 *
 * Cách chạy:
 *   1. Bật Redis + auth-service + catalog-service thật (xem README.md mục 6).
 *   2. Chạy class này: mvn -q exec:java -Dexec.mainClass=com.example.catalog.client.CatalogClientDemo
 *      (hoặc chạy thẳng main() từ IDE).
 *
 * XÁC THỰC: catalog-service giờ từ chối mọi lời gọi không có JWT hợp lệ. Client này đóng vai
 * 1 SERVICE nội bộ: tự đăng nhập vào auth-service bằng client_id + client_secret
 * (OAuth2 client credentials), nhận JWT, rồi đính "authorization: Bearer ..." vào mọi RPC.
 */
public class CatalogClientDemo {

    // Mặc định khớp dữ liệu mồi trong auth-service/application.yml. Đổi bằng biến môi trường
    // để KHÔNG phải sửa code - secret thật không bao giờ được viết cứng trong source.
    private static final String AUTH_URL = env("AUTH_URL", "http://localhost:8090");
    private static final String CLIENT_ID = env("CATALOG_CLIENT_ID", "catalog-demo-client");
    private static final String CLIENT_SECRET = env("CATALOG_CLIENT_SECRET", "catalog-demo-secret");
    private static final String READONLY_ID = "catalog-readonly-client";
    private static final String READONLY_SECRET = env("CATALOG_READONLY_SECRET", "catalog-readonly-secret");

    public static void main(String[] args) throws Exception {
        // ManagedChannelBuilder.forAddress(host, port): channel gRPC THẬT, kết nối qua
        // mạng (khác InProcessChannelBuilder dùng trong test). usePlaintext(): tắt TLS -
        // CHỈ chấp nhận được khi test cục bộ, production luôn phải bật TLS cho gRPC. QUAN TRỌNG:
        // không có TLS thì JWT đi trên dây dạng chữ thường - ai nghe lén được mạng là lấy được
        // token và dùng lại. Xác thực bằng token chỉ có ý nghĩa thật khi đi kèm TLS.
        ManagedChannel channel = ManagedChannelBuilder.forAddress("localhost", 9090)
                .usePlaintext()
                .build();

        try {
            authDemo(channel);

            String token = fetchToken(CLIENT_ID, CLIENT_SECRET, null);
            Channel authenticated = io.grpc.ClientInterceptors.intercept(channel, bearer(token));

            CatalogServiceGrpc.CatalogServiceBlockingStub blockingStub = CatalogServiceGrpc.newBlockingStub(authenticated);
            CatalogServiceGrpc.CatalogServiceStub asyncStub = CatalogServiceGrpc.newStub(authenticated);

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

    // ---- 0) XÁC THỰC: 3 kết quả khác nhau cho cùng 1 lời gọi, tuỳ "bạn là ai" ----
    private static void authDemo(ManagedChannel channel) throws Exception {
        System.out.println("\n=== 0) XÁC THỰC: cùng RPC, 3 kiểu người gọi ===");

        // a) Không token -> bị chặn ngay ở JwtAuthInterceptor, chưa chạm tới logic nghiệp vụ
        tryCall("không có token         ", () ->
                CatalogServiceGrpc.newBlockingStub(channel)
                        .getProduct(GetProductRequest.newBuilder().setSku("KHONG-CO").build()));

        // b) Token chỉ có catalog:read -> đọc được, nhưng ghi thì bị PERMISSION_DENIED
        String readOnlyToken = fetchToken(READONLY_ID, READONLY_SECRET, null);
        var readOnly = CatalogServiceGrpc.newBlockingStub(io.grpc.ClientInterceptors.intercept(channel, bearer(readOnlyToken)));
        tryCall("token chỉ đọc, GetProduct", () ->
                readOnly.getProduct(GetProductRequest.newBuilder().setSku("KHONG-CO").build()));
        tryCall("token chỉ đọc, CreateProduct", () ->
                readOnly.createProduct(CreateProductRequest.newBuilder().setSku("X").setName("x").build()));
    }

    private static void tryCall(String label, Runnable call) {
        try {
            call.run();
            System.out.println(label + " -> OK");
        } catch (StatusRuntimeException e) {
            // NOT_FOUND ở đây là tin tốt: nghĩa là đã QUA cổng xác thực, tới được logic nghiệp vụ
            // (sản phẩm "KHONG-CO" đúng là không tồn tại).
            System.out.println(label + " -> " + e.getStatus().getCode() + " (" + e.getStatus().getDescription() + ")");
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

    // ---------------------------------------------------------------------------------
    // Xác thực phía client
    // ---------------------------------------------------------------------------------

    /**
     * OAuth2 client credentials: "tôi là service X, đây là secret của tôi, cho tôi token".
     * scope = null -> nhận đủ các scope client này được phép. Token chỉ sống vài phút; demo xin
     * 1 lần cho gọn, client chạy lâu phải xin lại khi sắp hết hạn (xem AuthServiceTokenProvider
     * bên order-service cho cách làm đầy đủ: cache + tự làm mới).
     */
    private static String fetchToken(String clientId, String clientSecret, String scope) throws Exception {
        StringBuilder form = new StringBuilder("grant_type=client_credentials")
                .append("&client_id=").append(urlEncode(clientId))
                .append("&client_secret=").append(urlEncode(clientSecret));
        if (scope != null) {
            form.append("&scope=").append(urlEncode(scope));
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(AUTH_URL + "/oauth2/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException("auth-service từ chối (" + response.statusCode() + "): " + response.body());
        }
        return new ObjectMapper().readTree(response.body()).get("access_token").asText();
    }

    /** ClientInterceptor: đính "authorization: Bearer <token>" vào header của MỌI RPC đi qua channel này. */
    private static ClientInterceptor bearer(String token) {
        Metadata.Key<String> authorization = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
        return new ClientInterceptor() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                    MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
                return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<RespT> responseListener, Metadata headers) {
                        headers.put(authorization, "Bearer " + token);
                        super.start(responseListener, headers);
                    }
                };
            }
        };
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() ? value : defaultValue;
    }
}
