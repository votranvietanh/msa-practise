# Catalog Service — Bài học riêng về gRPC + Redis nâng cao

Service này **KHÔNG tham gia vào Saga** của 3 service kia (order/payment/inventory) — nó là
1 module học tập độc lập, đứng cạnh order-saga-demo, tập trung 100% vào 2 công nghệ:
**gRPC** (đủ cả 4 kiểu RPC) và **Redis** (đi sâu hơn hẳn cache đơn giản đã dùng ở
`order-service`). Đọc [README.md](../README.md) ở thư mục gốc trước nếu bạn chưa quen với
phong cách code/comment của repo này.

> Domain: quản lý sản phẩm + tồn kho cho 1 "Product Catalog" — khác với `inventory-service`
> (chỉ tham gia đúng 1 việc: trừ kho khi Saga chạy qua RabbitMQ), catalog-service đóng vai
> nơi khách hàng/hệ thống khác tra cứu sản phẩm, theo dõi tồn kho real-time, và admin nhập
> kho hàng loạt — 1 bounded context tách biệt, hoàn toàn hợp lý khi 2 service này không cần
> biết tới nhau.

---

## 1. Vì sao gRPC ở đây, REST ở 3 service kia?

Không phải vì gRPC "tốt hơn" REST một cách tuyệt đối — là 2 công cụ khác nhau cho 2 bài toán
khác nhau, và đây chính là điều nên rút ra sau khi đọc cả 2 phần của repo:

| | REST (order/payment/inventory-service) | gRPC (catalog-service) |
|---|---|---|
| Định dạng dữ liệu | JSON (text, người đọc được) | Protobuf (binary, nhỏ + nhanh hơn, phải có .proto mới đọc được) |
| Giao tiếp | Luôn luôn 1 request -> 1 response | 4 kiểu: unary, server-streaming, client-streaming, bidi-streaming |
| Hợp đồng API | Không bắt buộc (dễ để lệch ngầm giữa client/server) | `.proto` là hợp đồng CỨNG, sinh code cả 2 phía từ CÙNG 1 nguồn |
| Phù hợp nhất cho | API public, browser gọi trực tiếp, cần dễ debug bằng mắt | Giao tiếp service-to-service nội bộ, cần streaming, cần hiệu năng cao |

`order-saga-demo` dùng REST cho request từ Frontend (`POST /orders`) — hợp lý vì Frontend
(browser/mobile) không hỗ trợ gRPC-Web trực tiếp dễ dàng. catalog-service giả định người gọi
là 1 service/admin-tool nội bộ khác — nơi gRPC phát huy đúng thế mạnh.

---

## 2. Thứ tự đọc code

1. [`src/main/proto/catalog.proto`](src/main/proto/catalog.proto) — đọc TRƯỚC TIÊN, đây là
   "hợp đồng" toàn bộ service. Đọc comment ở từng `rpc` để hiểu tại sao chọn đúng kiểu đó.
2. `pom.xml` — đặc biệt phần `protobuf-maven-plugin`: bước biến `.proto` thành code Java
   (chạy `mvn compile` 1 lần để thấy `target/generated-sources/protobuf/` xuất hiện).
3. `grpc/CatalogGrpcService.java` — implementation thật của cả 6 rpc, đọc theo đúng thứ tự
   unary -> server-streaming -> client-streaming -> bidi-streaming (comment giải thích rõ
   signature Java của từng kiểu khác nhau thế nào).
4. `redis/RedisStockService.java` — Lua script atomic (kỹ thuật Redis "cao cấp" nhất ở đây).
5. `redis/RedisLeaderboardService.java` — Sorted Set.
6. `redis/RedisProductWatchBridge.java` + `redis/RedisProductEventPublisher.java` — Pub/Sub,
   cầu nối sang server-streaming gRPC.
7. `redis/RedisRateLimiter.java` + `grpc/RateLimitInterceptor.java` — rate limiting + cách
   gắn nó vào toàn bộ service qua interceptor (không sửa từng method).
8. `grpc/GrpcServerLifecycle.java` — cách 1 gRPC server thật sự được khởi động (không dùng
   thư viện tích hợp sẵn, xem trực tiếp `ServerBuilder`).
9. `client/CatalogClientDemo.java` — góc nhìn NGƯỢC LẠI: cách 1 client gọi cả 4 kiểu RPC.

---

## 3. 4 kiểu RPC — bảng tra nhanh

| Kiểu | Method trong `catalog.proto` | Java client thấy gì | Dùng khi nào |
|---|---|---|---|
| Unary | `CreateProduct`, `GetProduct`, `CheckStock`, `GetTopSellers` | Gọi như 1 method bình thường, có return value | Request/response đơn giản — mặc định nên chọn kiểu này trừ khi có lý do rõ ràng để streaming |
| Server streaming | `WatchProduct` | 1 lần gọi, nhận `StreamObserver` báo nhiều lần | Server cần chủ động đẩy dữ liệu về theo thời gian (subscribe/theo dõi) |
| Client streaming | `BulkRestock` | Gọi trả về 1 `StreamObserver` để MÌNH tự đẩy dữ liệu vào | Client có nhiều dữ liệu cần gửi dần (upload, nhập liệu hàng loạt) |
| Bidirectional streaming | `ReserveStockSession` | Cả 2 chiều đều là `StreamObserver` | 2 bên cần trao đổi liên tục, độc lập, không theo nhịp request/response cứng |

**Lỗi hay gặp nhất khi mới học streaming** (đọc kỹ code + test để thấy cách tránh):
- Quên gọi `onCompleted()` phía client streaming -> server treo mãi mãi chờ dữ liệu tiếp theo,
  không bao giờ trả response (xem `CatalogClientDemo.clientStreamingDemo`).
- Quên `setOnCancelHandler` phía server streaming -> rò rỉ tài nguyên (ở đây là 1 Redis
  subscription) khi client ngắt kết nối giữa chừng (xem `CatalogGrpcService.watchProduct`).
- Gọi `onNext()` trên `StreamObserver` từ nhiều thread khác nhau mà không đồng bộ hoá -
  `StreamObserver` KHÔNG thread-safe (xem comment trong `watchProduct`).

---

## 4. Redis nâng cao — pattern nào dùng ở đâu

| Pattern | File | So với order-saga-demo |
|---|---|---|
| Lua script atomic (`EVAL`) | `RedisStockService.tryReserve` | order-saga-demo chỉ dùng `SETNX` đơn giản cho idempotency; ở đây dùng hẳn 1 Lua script để gộp "kiểm tra + trừ" thành 1 bước atomic |
| Sorted Set (`ZINCRBY`/`ZREVRANGE`) | `RedisLeaderboardService` | Chưa xuất hiện ở order-saga-demo — ví dụ kinh điển cho bài toán "top N" |
| Pub/Sub | `RedisProductEventPublisher` + `RedisProductWatchBridge` | order-saga-demo dùng RabbitMQ cho mọi việc nhắn tin; ở đây thấy rõ khi nào Pub/Sub (best-effort, không lưu) hợp lý hơn message queue (đảm bảo, có lưu) |
| Rate limiting (`INCR`+`EXPIRE`) | `RedisRateLimiter` | Chưa xuất hiện ở order-saga-demo |

**So sánh Redis Pub/Sub vs RabbitMQ (rất hay bị hỏi khi phỏng vấn):**
Redis Pub/Sub KHÔNG lưu message — nếu không có subscriber nào đang lắng nghe đúng lúc
`PUBLISH`, message biến mất vĩnh viễn. RabbitMQ (dùng ở order-saga-demo) LƯU message trong
queue cho tới khi có consumer xử lý, kể cả khi consumer đang offline lúc publish. Vì vậy:
Pub/Sub chỉ hợp cho dữ liệu "miss cũng không sao" (thông báo real-time best-effort, như
`WatchProduct` ở đây); bất cứ thứ gì cần đảm bảo chắc chắn xử lý (như charge tiền, trừ kho
transactional) phải qua message queue thật hoặc DB transaction — không bao giờ dùng Pub/Sub
cho việc đó.

---

## 5. Testing

`CatalogGrpcServiceTest` dùng `InProcessServerBuilder`/`InProcessChannelBuilder` — gRPC chạy
hoàn toàn trong 1 JVM, không mở cổng TCP thật, nhanh và không xung đột port. `RedisStockServiceTest`,
`RedisLeaderboardServiceTest`, `RedisRateLimiterTest` mock `StringRedisTemplate` (không cần
Redis thật). `RateLimitInterceptorTest` verify interceptor qua chính 1 in-process server.

```bash
mvn test   # 25 test, không cần Redis/gRPC thật chạy nền
```

---

## 6. Chạy thử thật

```bash
# 1. Redis (dùng chung docker-compose.yml ở thư mục gốc)
cd ..
docker compose up -d
cd catalog-service

# 2. Chạy server
mvn spring-boot:run    # gRPC lắng nghe port 9090

# 3. (terminal khác) Liệt kê API bằng grpcurl - không cần file .proto nhờ Server Reflection
grpcurl -plaintext localhost:9090 list
grpcurl -plaintext localhost:9090 catalog.CatalogService/GetTopSellers

# 4. Hoặc chạy client demo có sẵn (gọi đủ cả 4 kiểu RPC, in kết quả ra console)
mvn exec:java -Dexec.mainClass=com.example.catalog.client.CatalogClientDemo
```

Thử rate limiting: gọi `CreateProduct` liên tục hơn 5 lần trong 10 giây (vd chạy
`CatalogClientDemo` vài lần liên tiếp) sẽ thấy lỗi `RESOURCE_EXHAUSTED` từ interceptor.
