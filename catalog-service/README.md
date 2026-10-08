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
8. `grpc/JwtAuthInterceptor.java` + `grpc/CallerContext.java` — XÁC THỰC + PHÂN QUYỀN cho gRPC: đọc
   JWT từ metadata `authorization`, mỗi RPC cần 1 scope (`catalog:read|write|reserve`), ghi người gọi
   vào `io.grpc.Context`. Tương đương Spring Security bên REST (xem README gốc, mục 13).
9. `grpc/GrpcServerLifecycle.java` — cách 1 gRPC server thật sự được khởi động (không dùng
   thư viện tích hợp sẵn, xem trực tiếp `ServerBuilder`), cách xếp thứ tự interceptor (có bẫy!) và
   vì sao phải có thread giữ JVM sống.
10. `client/CatalogClientDemo.java` — góc nhìn NGƯỢC LẠI: cách 1 client tự xin token rồi gọi cả 4 kiểu RPC.

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

## 4. Redis — toàn cảnh cả repo (4 service) + đi sâu ở catalog-service

### 4.1. Toàn cảnh: Redis dùng ở đâu, để làm gì, trong TỪNG service

Cả repo có **7 usecase Redis** trải trên 4 service, cố tình đi từ dễ (lặp lại 1 pattern ở
nhiều nơi) tới khó (mỗi kỹ thuật xuất hiện đúng 1 lần, không trùng nhau):

| # | Service | Usecase | Redis feature | File |
|---|---|---|---|---|
| 1 | `order-service` | Cache kết quả `GET /orders/{id}/status` — endpoint bị Frontend polling dồn dập | Cache-aside qua `@Cacheable`/`@CacheEvict` (Spring Cache, backend `RedisCacheManager`), TTL 5s | [OrderQueryService.java](../order-service/src/main/java/com/example/order/service/OrderQueryService.java) |
| 2 | `payment-service` | Chống charge tiền 2 lần khi RabbitMQ redeliver message, kể cả khi chạy nhiều instance | Distributed lock qua `SETNX` (`opsForValue().setIfAbsent`) | [InMemoryPaymentGateway.java](../payment-service/src/main/java/com/example/payment/service/InMemoryPaymentGateway.java) |
| 3 | `payment-service` | Chống hoàn tiền 2 lần (cùng cơ chế, khác key) | `SETNX` | (cùng file trên) |
| 4 | `inventory-service` | Chống trừ kho 2 lần cho cùng 1 order khi message redeliver | `SETNX` | [InMemoryInventoryRepository.java](../inventory-service/src/main/java/com/example/inventory/repository/InMemoryInventoryRepository.java) |
| 5 | `catalog-service` | Trừ kho atomic: gộp "kiểm tra đủ hàng + trừ" thành 1 bước, không hở race condition | **Lua script** (`EVAL`) | [RedisStockService.java](src/main/java/com/example/catalog/redis/RedisStockService.java) |
| 6 | `catalog-service` | Bảng xếp hạng sản phẩm bán chạy, tự sắp xếp sẵn theo tổng số lượng bán | **Sorted Set** (`ZINCRBY`/`ZREVRANGE`) | [RedisLeaderboardService.java](src/main/java/com/example/catalog/redis/RedisLeaderboardService.java) |
| 7 | `catalog-service` | Đẩy cập nhật giá/tồn kho real-time cho client đang `WatchProduct` (gRPC server-streaming) | **Pub/Sub** | [RedisProductEventPublisher.java](src/main/java/com/example/catalog/redis/RedisProductEventPublisher.java), [RedisProductWatchBridge.java](src/main/java/com/example/catalog/redis/RedisProductWatchBridge.java) |
| 8 | `catalog-service` | Giới hạn số lần gọi `CreateProduct` trong 1 khoảng thời gian | **Rate limiting** (`INCR`+`EXPIRE`, fixed window) | [RedisRateLimiter.java](src/main/java/com/example/catalog/redis/RedisRateLimiter.java) |

**Vì sao 2/1/3/4 dùng cùng 1 pattern (SETNX)?** Đây là điểm cố ý: idempotency (chống xử lý
trùng khi message bị redeliver) là bài toán XUẤT HIỆN LẶP LẠI ở bất kỳ đâu có side-effect
thật (trừ tiền, trừ kho...) trong hệ thống event-driven — học 1 lần, áp dụng nhất quán ở
nhiều nơi, thay vì mỗi chỗ tự nghĩ ra 1 cách khác nhau.

### 4.2. Đi sâu ở catalog-service — pattern nào, so với order-saga-demo

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

Phần bảo mật: `JwtAuthInterceptorTest` đi qua in-process server với JWT thật (ký bằng khoá sinh trong
test, xem `TestTokens`) và phủ các trường hợp: thiếu header, sai scheme, token rác/hết hạn/ký bằng khoá
lạ/sai issuer -> `UNAUTHENTICATED`; thiếu scope -> `PERMISSION_DENIED`; lỗi hạ tầng khi tải khoá ->
`UNAVAILABLE` (không báo nhầm là token sai). Có test canh việc mọi RPC trong `.proto` đều đã khai báo scope.
`InterceptorOrderTest` khoá cứng thứ tự interceptor (xem bẫy `intercept` vs `interceptForward`).

```bash
mvn test   # 42 test, không cần Redis/gRPC/auth-service thật chạy nền
```

---

## 6. Chạy thử thật

```bash
# 1. Redis (dùng chung docker-compose.yml ở thư mục gốc)
cd ..
docker compose up -d
cd catalog-service

# 2. auth-service (phát token) rồi catalog-service - mỗi cái 1 terminal
cd ../auth-service && mvn spring-boot:run     # port 8090
cd ../catalog-service && mvn spring-boot:run  # gRPC lắng nghe port 9090

# 3. (terminal khác) Liệt kê API bằng grpcurl - không cần file .proto nhờ Server Reflection
#    (reflection KHÔNG cần token, nhưng GỌI API thì phải có)
grpcurl -plaintext localhost:9090 list

# 4. Xin token rồi gọi API (không token -> UNAUTHENTICATED)
TOKEN=$(curl -s -X POST localhost:8090/oauth2/token \
  -d "grant_type=client_credentials&client_id=catalog-demo-client&client_secret=catalog-demo-secret" \
  | sed -E 's/.*"access_token":"([^"]+)".*/\1/')
grpcurl -plaintext -H "authorization: Bearer $TOKEN" localhost:9090 catalog.CatalogService/GetTopSellers

# 5. Hoặc chạy client demo có sẵn: tự xin token, in 3 kết quả xác thực khác nhau rồi gọi đủ cả 4 kiểu RPC
mvn exec:java -Dexec.mainClass=com.example.catalog.client.CatalogClientDemo
```

Thử rate limiting: gọi `CreateProduct` liên tục hơn 5 lần trong 10 giây (vd chạy
`CatalogClientDemo` vài lần liên tiếp) sẽ thấy lỗi `RESOURCE_EXHAUSTED` từ interceptor - hạn mức tính
RIÊNG cho từng client (`sub` của token), nên client khác không bị chặn lây.

Client demo dùng secret mặc định của dữ liệu mồi trong `auth-service/application.yml`; đổi bằng biến môi
trường `CATALOG_CLIENT_SECRET`, `CATALOG_READONLY_SECRET`, `AUTH_URL`. Lưu ý: chưa bật TLS nên token vẫn
đi dạng chữ thường trên mạng (xem README gốc, mục 13.5).
