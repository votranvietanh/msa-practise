# Order Processing Saga — Demo Codebase (3 Microservices + RabbitMQ)

Codebase minh họa luồng xử lý Order theo mô hình **Saga Choreography**,
giao tiếp giữa 3 service qua **RabbitMQ (Topic Exchange)**.

> **Repo này có 4 service.** 3 service `order-service`/`payment-service`/`inventory-service`
> tạo thành 1 Saga hoàn chỉnh (nội dung README này). `catalog-service` là module RIÊNG,
> KHÔNG tham gia Saga — bài học độc lập tập trung sâu vào **gRPC** (đủ 4 kiểu RPC) và
> **Redis nâng cao** (Lua script atomic, Sorted Set, Pub/Sub, rate limiting). Xem
> [catalog-service/README.md](catalog-service/README.md).
>
> `rest-vs-grpc-demo` cũng là module riêng: đặt REST và gRPC cạnh nhau cho CÙNG 1 case
> (Order hỏi giá + tồn kho từ Catalog) để thấy rõ khác biệt - xem
> [rest-vs-grpc-demo/README.md](rest-vs-grpc-demo/README.md).
>
> `auth-service` là nơi PHÁT JWT (đăng nhập người dùng + OAuth2 client credentials cho service).
> `order-service`, `payment-service`, `catalog-service` đều bắt buộc có token hợp lệ - xem
> [mục 13](#13-xác-thực--phân-quyền-jwt). (`rest-vs-grpc-demo` vẫn KHÔNG có xác thực.)

> Mục đích: đọc để hiểu FLOW, tập nhận diện lỗi thường gặp khi làm hệ thống event-driven,
> và làm ví dụ cho các kỹ năng hay xuất hiện trong JD backend (Saga, RabbitMQ, Redis,
> SOLID, unit test, reconciliation/reporting). Code build/test được thật (xem mục 11),
> nhưng vẫn ưu tiên tối giản — lược bỏ validation/exception handling thừa để tập trung
> vào luồng chính, không phải 1 bộ khung sẵn sàng deploy production.
> Các đoạn code "lạ" với người mới (manual ack/nack, DLX, idempotent check qua Redis,
> message converter, @Cacheable...) đều có comment giải thích ngay tại chỗ — đọc code
> trước, đọc README này để nối lại bức tranh tổng.

---

## 1. Kiến trúc tổng quan

```
                      ┌─────────────────┐
   Frontend  ───REST──▶  Order Service   │
                      └────────┬─────────┘
                               │ publish: order.created
                               ▼
                    ┌───────────────────────┐
                    │ order.topic.exchange  │  (RabbitMQ)
                    └───────────┬───────────┘
                                │ route theo routing key
              ┌─────────────────┼──────────────────┐
              ▼                                     ▼
     ┌─────────────────┐                  ┌─────────────────────┐
     │ Payment Service  │──publish──▶      │ order.status.queue  │──▶ Order Service
     │  (payment.queue) │  payment.success │ (mọi kết quả cuối)  │   (update DB status)
     └────────┬─────────┘  payment.failed  └─────────────────────┘
              │ publish: payment.success (kèm "items")
              ▼
     ┌─────────────────────┐
     │ Inventory Service    │──publish──▶ inventory.reserved / inventory.failed
     │ (inventory.queue)    │             (route tới order.status.queue)
     └──────────────────────┘

   Compensate (khi Inventory hết hàng):
   Order Service ──publish: payment.refund──▶ Payment Service ──publish: payment.refunded──▶ order.status.queue
```

**3 service độc lập, mỗi service:**
- Có package/module riêng (giả lập DB riêng — trong demo dùng in-memory Map thay vì DB thật để dễ chạy đọc, không cần cài PostgreSQL).
- Không gọi trực tiếp REST API lẫn nhau — chỉ giao tiếp qua message.
- Có `RabbitConfig` riêng khai báo exchange/queue/binding của chính nó, **kể cả Dead Letter Exchange** (mỗi service tự khai báo lại `order.dlx` — giống cách mỗi service tự khai báo lại `order.topic.exchange` — vì mỗi service là 1 process/Spring context riêng biệt, khai báo lại cùng 1 exchange nhiều lần là bình thường và an toàn trong RabbitMQ).

---

## 2. Thứ tự đọc code để theo dõi đúng luồng (đọc theo số thứ tự)

### Bước 1 — Order Service nhận request, publish event đầu tiên
1. `order-service/.../controller/OrderController.java` — endpoint `POST /orders` (vì sao trả 202 Accepted thay vì 200 OK)
2. `order-service/.../service/OrderService.java` — lưu DB (status=PENDING) + gọi publisher
3. `order-service/.../publisher/OrderEventPublisher.java` — publish `order.created` (chú ý `MessageDeliveryMode.PERSISTENT`)
4. `order-service/.../config/RabbitConfig.java` — xem exchange/queue/binding + Dead Letter Exchange được khai báo ra sao

### Bước 2 — Payment Service xử lý thanh toán
5. `payment-service/.../config/RabbitConfig.java` — binding `payment.queue` nhận `order.created`, DLX thật (`order.dlx` + `*.dlq`)
6. `payment-service/.../listener/PaymentListener.java` — xử lý, publish `payment.success` (kèm `items` để Inventory dùng) / `payment.failed`, ghi vào ledger
7. `payment-service/.../service/PaymentGateway.java` (interface) + `InMemoryPaymentGateway.java` (impl) — giả lập gọi cổng thanh toán, idempotent qua Redis (xem mục 7)
8. `payment-service/.../listener/RefundListener.java` — xử lý compensate khi Inventory fail, publish `payment.refunded`

### Bước 3 — Inventory Service trừ kho
9. `inventory-service/.../config/RabbitConfig.java` — binding `inventory.queue` nhận `payment.success`
10. `inventory-service/.../listener/InventoryListener.java` — trừ kho, publish `inventory.reserved` / `inventory.failed`
11. `inventory-service/.../repository/InventoryRepository.java` (interface) + `InMemoryInventoryRepository.java` (impl) — in-memory stock, idempotent qua Redis + thread-safe (`synchronized`)

### Bước 4 — Order Service nhận kết quả cuối, cập nhật status
12. `order-service/.../listener/OrderStatusListener.java` — lắng nghe TẤT CẢ event kết quả cuối (`payment.success`,
    `payment.failed`, `inventory.reserved`, `inventory.failed`, `payment.refunded`), update DB, trigger compensate
    (`payment.refund`) nếu Inventory fail. Đọc kỹ comment ở đầu file để hiểu vì sao method này nhận raw `Message`
    thay vì 1 class event cụ thể như 2 listener kia.

### Bước 5 — Frontend polling
13. `order-service/.../controller/OrderController.java` — endpoint `GET /orders/{id}/status`, đọc qua `OrderQueryService` (có cache Redis, xem mục 7)

### Bước 6 — Phần mở rộng cho vận hành (không thuộc luồng Saga chính)
14. `payment-service/.../ledger/` — sổ cái giao dịch tiền (ghi mỗi lần charge/refund), expose qua `GET /payments/ledger`
15. `order-service/.../service/ReconciliationService.java` — đối soát Order Service với ledger của Payment Service
16. `order-service/.../service/ReportService.java` — báo cáo tổng hợp số lượng/doanh thu theo status

---

## 3. Bảng Routing Key — "bản đồ" của toàn bộ hệ thống

| Routing Key         | Publisher          | Queue nhận              | Consumer            |
|----------------------|---------------------|--------------------------|----------------------|
| `order.created`      | Order Service       | `payment.queue`          | Payment Service      |
| `payment.success`    | Payment Service     | `inventory.queue`        | Inventory Service    |
| `payment.success`    | Payment Service     | `order.status.queue`     | Order Service        |
| `payment.failed`     | Payment Service     | `order.status.queue`     | Order Service        |
| `inventory.reserved` | Inventory Service   | `order.status.queue`     | Order Service        |
| `inventory.failed`   | Inventory Service   | `order.status.queue`     | Order Service        |
| `payment.refund`     | Order Service       | `payment.refund.queue`   | Payment Service      |
| `payment.refunded`   | Payment Service     | `order.status.queue`     | Order Service        |

Nếu bạn muốn biết "service X nhận được gì, từ ai" — tra bảng này trước, rồi mới đọc code.

**Mẹo debug khi tự thêm event mới:** mỗi routing key publish ra phải có ÍT NHẤT 1 hàng trong bảng này
với 1 Binding tương ứng trong code. Nếu publish 1 routing key mà không queue nào bind tới, RabbitMQ
Topic Exchange sẽ **âm thầm làm rớt message** — không lỗi, không log, rất khó phát hiện (bản demo này
từng dính đúng lỗi đó với `payment.refunded`, xem mục 5 bên dưới).

---

## 4. Pattern áp dụng trong codebase này

| Pattern | Vị trí trong code |
|---|---|
| Saga Choreography | Không có orchestrator — mỗi Listener tự publish event tiếp theo |
| Event-Driven Architecture | Toàn bộ giao tiếp backend-to-backend qua RabbitMQ event |
| Compensating Transaction | `RefundListener.java` — hoàn tiền khi `payment.refund` được publish, xác nhận lại qua `payment.refunded` |
| Anti-Corruption Layer (rút gọn) | Mỗi service có package `event/` riêng — không share chung 1 class DTO giữa các service, dù nội dung field giống nhau (mô phỏng nguyên tắc mỗi service tự định nghĩa model riêng). **Rủi ro đi kèm pattern này:** dễ quên đồng bộ field giữa 2 DTO tưởng giống nhau — xem mục 5 |
| Idempotent Consumer | `InMemoryInventoryRepository.java` và `InMemoryPaymentGateway.java` — check theo `orderId` qua **Redis** (`SETNX`) trước khi trừ kho/charge/refund, tránh xử lý trùng nếu message bị redeliver, kể cả khi service chạy nhiều instance (xem mục 7) |
| Dead Letter Queue | Mỗi `RabbitConfig.java` khai báo `x-dead-letter-exchange` TRÊN QUEUE **và** khai báo luôn exchange `order.dlx` + queue `*.dlq` thật để hứng message chết — 2 việc này phải đi cùng nhau, chỉ set argument thôi là chưa đủ |
| 202 Accepted + Polling | `OrderController.createOrder()` trả ngay, không chờ Saga chạy xong |
| Dependency Inversion (SOLID) | `OrderRepository`, `InventoryRepository`, `PaymentGateway`, `PaymentLedger`, `PaymentLedgerClient` đều là **interface** — service/listener chỉ phụ thuộc vào interface, không phụ thuộc thẳng implementation (xem mục 6) |
| Cache-aside qua Redis | `OrderQueryService.getStatus()` — cache kết quả `GET /orders/{id}/status`, TTL ngắn + evict thủ công khi status đổi (xem mục 7) |
| Reconciliation / Reporting | `ReconciliationService.java`, `ReportService.java` — đối soát dữ liệu giữa 2 service và báo cáo tổng hợp (xem mục 8) |

---

## 5. Lỗi thường gặp khi làm Saga (đã từng có trong bản demo này — dùng để dạy nhận diện lỗi)

Bản demo ban đầu có 3 lỗi khá điển hình khi mới làm event-driven/Saga. Cả 3 đều **không crash lúc
start service**, chỉ lộ ra khi thực sự chạy luồng — kiểu lỗi khó chịu nhất vì không compile-time-safe.
Rất đáng đọc qua để tránh lặp lại khi tự viết hệ thống tương tự.

### 5.1. Field bị "rớt" giữa 2 DTO tưởng giống nhau (Anti-Corruption Layer risk)
`order.created` mang theo `items` (danh sách sản phẩm), nhưng bản `PaymentSuccessEvent` cũ mà
Payment Service publish ra chỉ có `orderId/userId/amount` — thiếu hẳn `items`. Vì mỗi service tự định
nghĩa DTO riêng (đúng tinh thần ACL), không có compiler nào báo lỗi cả — JSON gửi đi thiếu field, phía
Inventory Service deserialize `items` thành `null`, rồi NullPointerException ngay khi vòng lặp trừ kho
chạy tới. Hậu quả: **toàn bộ order, kể cả còn hàng, đều bị lỗi và rơi vào DLQ**, không bao giờ có
`inventory.reserved` — nhánh "happy path" coi như không chạy được.

**Bài học:** khi 1 service chỉ đóng vai trò trung chuyển (relay) dữ liệu nó không trực tiếp dùng, vẫn
phải đảm bảo field đó được giữ lại nguyên vẹn trong event publish tiếp theo. `PaymentListener.java` giờ
map tường minh `items` từ `OrderCreatedEvent` sang `PaymentSuccessEvent` để việc này hiện rõ trong code,
không bị "quên" một cách âm thầm.

### 5.2. Publish event nhưng quên bind queue nhận (event "mồ côi")
`RefundListener` publish `payment.refunded` sau khi hoàn tiền xong, nhưng `order-service`'s
`RabbitConfig` chưa từng bind `order.status.queue` với routing key này. Topic Exchange không tìm được
queue nào khớp → message bị rớt trong im lặng, không exception, không log lỗi. Order Service không bao
giờ biết được là tiền đã hoàn xong.

**Bài học:** mỗi khi publish 1 routing key mới, luôn tự hỏi "ai đang bind để nhận nó?" — nếu không ai
nhận, hoặc là thừa code publish, hoặc là thiếu binding. Đã bổ sung `bindPaymentRefunded()` +
`RefundCompletedEvent.java` + case `payment.refunded` trong `OrderStatusListener` để đóng vòng lặp này.

### 5.3. Khai báo DLX qua argument nhưng chưa từng khai báo exchange DLX thật
Các queue chính (`payment.queue`, `inventory.queue`, `payment.refund.queue`) đều có
`x-dead-letter-exchange: order.dlx`, nhưng **không có `@Bean` nào khai báo exchange `order.dlx`** và
cũng không có queue nào lắng nghe nó. RabbitMQ vẫn cho phép khai báo queue kiểu này (không lỗi lúc
start), nhưng khi message thật sự bị `basicNack(requeue=false)`, nó chỉ **biến mất** thay vì có chỗ để
dev vào kiểm tra lại nguyên nhân lỗi.

**Bài học:** khai báo dead-letter-exchange trên queue chỉ là "địa chỉ chuyển tới" — vẫn phải khai báo
exchange đó + ít nhất 1 queue thật bind vào nó thì mới thực sự có DLQ dùng được. Mỗi `RabbitConfig.java`
giờ đều có thêm `deadLetterExchange()` (DirectExchange `order.dlx`) + 1 `*.dlq` queue cho từng routing
key chết tương ứng.

### 5.4. Thiếu idempotency ở nơi có side-effect tiền bạc
`InventoryListener`/`InventoryRepository` có check `processedOrderIds` trước khi trừ kho, nhưng
`PaymentListener`/`RefundListener` ban đầu KHÔNG có cơ chế tương tự khi charge/refund tiền. RabbitMQ
theo mô hình **at-least-once delivery**: nếu consumer crash sau khi charge() thành công nhưng trước khi
kịp `basicAck`, message sẽ được gửi lại (redeliver) — nếu không check idempotent, user có thể bị **trừ
tiền hoặc hoàn tiền 2 lần** cho cùng 1 order.

**Bài học:** "Idempotent Consumer" nên áp dụng nhất quán ở MỌI nơi có side-effect thật (trừ tiền, trừ
kho, gửi email...), không chỉ 1 chỗ trong hệ thống. Cả `InMemoryPaymentGateway` và
`InMemoryInventoryRepository` giờ dùng chung 1 cơ chế idempotency qua **Redis** (không còn Set in-memory
nữa — xem mục 7 để hiểu tại sao Set in-memory không đủ khi service chạy nhiều instance).

---

## 6. Dependency Inversion — vì sao Repository/Gateway là interface

`OrderRepository`, `InventoryRepository`, `PaymentGateway`, `PaymentLedger`, `PaymentLedgerClient` đều
được khai báo thành **interface**, với implementation nằm ở 1 class riêng (`InMemoryXxx`/`RestXxx`).
Service/Listener chỉ khai báo phụ thuộc vào interface qua constructor:

```java
public class OrderService {
    private final OrderRepository orderRepository; // interface, KHÔNG phải InMemoryOrderRepository
    ...
}
```

Đây là chữ **D** (Dependency Inversion) trong SOLID: module cấp cao (business logic trong Service/
Listener) không nên phụ thuộc vào module cấp thấp (chi tiết lưu trữ/gọi API), cả 2 nên cùng phụ thuộc
vào 1 abstraction. Lợi ích cụ thể thấy ngay trong repo này:

- **Test được mà không cần hạ tầng thật**: toàn bộ unit test (mục 10) mock thẳng các interface này bằng
  Mockito — không cần Redis/RabbitMQ/DB chạy nền khi chạy `mvn test`.
- **Đổi implementation không ảnh hưởng chỗ khác**: đổi `InMemoryOrderRepository` sang
  `JpaOrderRepository` (Spring Data JPA + PostgreSQL) chỉ cần viết class mới implement
  `OrderRepository`, không sửa `OrderService`/`OrderStatusListener`/`ReconciliationService`.

Các nguyên tắc SOLID khác trong repo (đọc code sẽ thấy rõ hơn là đọc README):
- **S** (Single Responsibility) — `OrderService` chỉ lo tạo order, `OrderQueryService` chỉ lo đọc/cache,
  `ReportService`/`ReconciliationService` mỗi class 1 loại báo cáo. Không có "God class" ôm hết logic.
- **O** (Open/Closed) — điểm CHƯA đạt được tốt: `OrderStatusListener.handle()` dùng `switch` theo routing
  key, thêm 1 loại event mới bắt buộc phải SỬA method này thay vì mở rộng bằng cách thêm class mới. Cố
  tình giữ nguyên (không refactor sang Strategy pattern) vì ở quy mô 5 loại event, switch vẫn dễ đọc hơn;
  đây là 1 trade-off thực tế đáng biết khi trả lời phỏng vấn, không phải lỗi.

---

## 7. Redis trong bản demo này

JD backend hay yêu cầu kinh nghiệm Redis cho "caching, session management, hoặc pub/sub". Demo này
dùng Redis cho **2 usecase thật, có lý do rõ ràng** — cố tình KHÔNG dùng Redis Pub/Sub vì RabbitMQ đã
đảm nhiệm đúng vai trò đó cho luồng Saga rồi; nhét thêm 1 message broker thứ 2 chỉ để "có dùng Redis
pub/sub" sẽ là over-engineering vô nghĩa, không phải kỹ năng đáng thể hiện.

### 7.1. Cache-aside cho endpoint bị polling dồn dập
`OrderQueryService.getStatus()` ([order-service](order-service/src/main/java/com/example/order/service/OrderQueryService.java))
cache kết quả `GET /orders/{id}/status` bằng `@Cacheable`/`@CacheEvict` (Spring Cache abstraction, backed
bởi `RedisCacheManager` — cấu hình ở `CacheConfig.java`). Đây là endpoint Frontend gọi liên tục theo kiểu
polling trong lúc chờ Saga chạy xong (xem mục 1) — cache giúp giảm tải cho nguồn dữ liệu gốc.

2 lớp phòng vệ chống dữ liệu cũ:
1. TTL ngắn (5 giây, xem `CacheConfig`) — tự hết hạn dù có quên evict ở đâu đó.
2. Evict thủ công ngay khi status đổi — `OrderStatusListener.updateStatus()` gọi
   `orderQueryService.evictStatusCache(orderId)` ngay sau khi Saga cập nhật xong.

### 7.2. Idempotency phân tán (lý do THẬT SỰ cần Redis, không chỉ "cache cho nhanh")
`InMemoryPaymentGateway.charge()/refund()` và `InMemoryInventoryRepository.tryReserve()` dùng
`StringRedisTemplate.opsForValue().setIfAbsent(key, "1", ttl)` — tương đương lệnh Redis
`SET key value NX EX <ttl>` — làm khoá chống trùng trước khi charge tiền / trừ kho.

Bản demo lúc đầu dùng `Set<String>` in-memory (`ConcurrentHashMap.newKeySet()`) cho việc này — CHỈ đúng
khi service chạy đúng 1 instance. Production luôn chạy nhiều instance để chịu tải/zero-downtime deploy,
mỗi instance có vùng nhớ riêng, nên `Set` in-memory của instance A không biết instance B đã xử lý order
này chưa — double-charge/double-reserve vẫn xảy ra khi scale ngang dù code "trông có vẻ" chống trùng.
Redis giải quyết đúng vấn đề này vì nó là 1 nơi lưu trạng thái DÙNG CHUNG giữa mọi instance, và
`SETNX` là thao tác atomic trên toàn cụm Redis.

**Giới hạn cố tình để lộ ra (đọc comment trong `InMemoryPaymentGateway.java`):** bản thân `balances`/
`stock` vẫn là Map in-memory riêng từng instance — Redis chỉ đảm bảo "không ai xử lý trùng 1 order",
KHÔNG đảm bảo số dư/tồn kho đồng bộ giữa nhiều instance. Hệ thống thật cần số dư/tồn kho nằm trong 1 DB
dùng chung (hoặc chính Redis với `INCR`/`DECR` atomic) mới an toàn tuyệt đối khi chạy nhiều instance.

### Muốn chạy thật với Redis
`docker compose up -d` (xem [docker-compose.yml](docker-compose.yml)) để có cả RabbitMQ lẫn Redis, xem mục 11.

---

## 8. Payment Ledger, Reconciliation & Reporting

3 tính năng vận hành (operations), tách biệt hoàn toàn khỏi luồng Saga chính (không publish/consume
event nào cả) — mô phỏng đúng bài toán thực tế "hệ thống thanh toán không chỉ cần chạy đúng, mà còn cần
CHỨNG MINH ĐƯỢC là nó chạy đúng".

### 8.1. Payment Ledger — sổ cái giao dịch
Mỗi lần charge (thành công/thất bại) hoặc refund, `PaymentListener`/`RefundListener` ghi 1 dòng vào
`PaymentLedger` ([payment-service](payment-service/src/main/java/com/example/payment/ledger)) - đây là
nguồn sự thật (source of truth) về việc tiền thực sự đã di chuyển hay chưa, tách biệt khỏi trạng thái
`Order` bên Order Service (vốn chỉ phản ánh "Order Service NGHĨ LÀ" Saga đã xong tới đâu).

Expose qua `GET /payments/ledger` và `GET /payments/ledger/{orderId}`.

### 8.2. Reconciliation — đối soát
`ReconciliationService` ([order-service](order-service/src/main/java/com/example/order/service/ReconciliationService.java))
gọi REST sang `GET /payments/ledger` (qua `RestClient`, xem `RestClientConfig`/`PaymentLedgerClient`) rồi
so khớp với dữ liệu Order Service đang giữ. Đây là **ngoại lệ DUY NHẤT** cho phép 2 service gọi REST
thẳng nhau trong repo này — vì đây là tác vụ đọc-only phục vụ vận hành, không phải 1 bước của giao dịch
nghiệp vụ, nên không vi phạm nguyên tắc "chỉ giao tiếp qua message" nói ở mục 1.

3 quy tắc đang được kiểm tra (xem code để rõ chi tiết từng case):
| Order status | Điều kiện bất thường | Ý nghĩa |
|---|---|---|
| `COMPLETED` | Không có giao dịch `CHARGE_SUCCESS` nào bên Payment | Order Service tưởng đã xong nhưng Payment Service không hề charge — lệch dữ liệu nghiêm trọng |
| `FAILED` | Có `CHARGE_SUCCESS` nhưng KHÔNG có `REFUND` | Tiền đã bị trừ do lỗi hết hàng nhưng CHƯA được hoàn — user có thể mất tiền oan |
| `PENDING` | Đã tạo quá lâu (`reconciliation.stuck-pending-threshold`, mặc định 5 phút) | Saga có thể đã "kẹt" giữa chừng (message mất, 1 service down...) |

Gọi `GET /orders/reconciliation` để chạy đối soát.

### 8.3. Reporting — báo cáo tổng hợp
`GET /orders/report/summary` trả về số lượng order theo từng status + tổng tiền COMPLETED/FAILED
(`ReportService.java`) — ví dụ đơn giản cho 1 dashboard vận hành.

---

## 9. Testing

Mỗi service có unit test riêng (JUnit 5 + Mockito + AssertJ, qua `spring-boot-starter-test`):

| Service | Test | Verify |
|---|---|---|
| order-service | `OrderServiceTest` | Tạo order đúng status PENDING + publish đúng event |
| order-service | `OrderStatusListenerTest` | Từng routing key cập nhật đúng status, trigger đúng compensate, evict cache đúng lúc |
| order-service | `OrderQueryServiceCacheTest` | Hành vi `@Cacheable`/`@CacheEvict` (dùng `ConcurrentMapCacheManager`, không cần Redis thật) |
| order-service | `ReportServiceTest` | Đếm/tổng tiền đúng theo status |
| order-service | `ReconciliationServiceTest` | Cả 3 quy tắc đối soát ở mục 8.2 |
| payment-service | `InMemoryPaymentGatewayTest` | Charge/refund + idempotent short-circuit + rollback key khi fail (mock `StringRedisTemplate`) |
| payment-service | `PaymentListenerTest`, `RefundListenerTest` | Điều phối đúng theo kết quả charge/refund, ghi ledger đúng (không trùng khi idempotent) |
| inventory-service | `InMemoryInventoryRepositoryTest` | Trừ kho, hết hàng, idempotent, rollback key (mock `StringRedisTemplate`) |
| inventory-service | `InventoryListenerTest` | Publish đúng event theo kết quả reserve |

Chạy test từng service:
```bash
cd order-service && mvn test
cd payment-service && mvn test
cd inventory-service && mvn test
```

**Phạm vi cố tình KHÔNG cover** (để không tự nhận vơ hơn thực tế): không có test verify Redis/RabbitMQ
THẬT hoạt động đúng khi serialize qua wire (unit test mock `StringRedisTemplate`) — muốn chắc chắn hơn
nữa thì cần thêm integration test dùng Testcontainers (Redis + RabbitMQ container thật), nằm ngoài phạm
vi bộ test hiện tại.

---

## 10. API Endpoints & OpenAPI

| Method | Endpoint | Service | Mục đích | Yêu cầu (JWT) |
|---|---|---|---|---|
| POST | `/auth/login` | auth-service | Người dùng đăng nhập, nhận JWT | (công khai) |
| POST | `/oauth2/token` | auth-service | Service xin token (client credentials) | client_id + client_secret |
| GET | `/oauth2/jwks` | auth-service | Khoá công khai để verify chữ ký | (công khai) |
| POST | `/orders` | order-service | Tạo order, khởi động Saga | role `USER` |
| GET | `/orders/{id}/status` | order-service | Frontend polling (có cache Redis) | role `USER` (chỉ đơn CỦA MÌNH) hoặc `ADMIN` |
| GET | `/orders/report/summary` | order-service | Báo cáo tổng hợp | role `ADMIN` |
| GET | `/orders/reconciliation` | order-service | Đối soát với Payment Service | role `ADMIN` |
| GET | `/payments/ledger` | payment-service | Toàn bộ sổ cái giao dịch | service token scope `ledger:read` |
| GET | `/payments/ledger/{orderId}` | payment-service | Sổ cái của 1 order | service token scope `ledger:read` |

order-service và payment-service tích hợp sẵn `springdoc-openapi` — sau khi chạy (mục 11), xem tài liệu
API tương tác tại `http://localhost:8081/swagger-ui.html` và `http://localhost:8082/swagger-ui.html`.

---

## 11. Chạy thử thật

Khác với bản demo lúc đầu (chỉ đọc code, không build được), repo giờ có `pom.xml` + `application.yml`
đầy đủ cho cả các service — build/chạy được thật.

```bash
# 1. Hạ tầng: RabbitMQ + Redis
docker compose up -d

# 2. Chạy từng service (mỗi cái 1 terminal, hoặc process nền). auth-service chạy TRƯỚC.
cd auth-service       && mvn spring-boot:run   # port 8090 - phát JWT
cd order-service      && mvn spring-boot:run   # port 8081
cd payment-service    && mvn spring-boot:run   # port 8082
cd inventory-service  && mvn spring-boot:run   # port 8083 (chỉ nhận message, chưa có REST nên chưa cần token)

# 3. Đăng nhập (tài khoản demo alice/bob/admin: xem auth-service/src/main/resources/application.yml)
TOKEN=$(curl -s -X POST http://localhost:8090/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice-pass"}' | sed -E 's/.*"access_token":"([^"]+)".*/\1/')

# 4. Thử happy path - KHÔNG gửi userId trong body, server lấy từ token (alice = U001)
curl -X POST http://localhost:8081/orders \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"amount":250000,"items":[{"sku":"ITEM-01","qty":2}]}'

# 5. Poll status bằng orderId trả về ở bước 4 (chỉ xem được đơn của chính mình)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/orders/ORD-XXXXXXXX/status
```

Gợi ý test nhanh các nhánh khác nhau (xem `PaymentGateway`/`InventoryRepository` để biết dữ liệu giả lập):
- Đăng nhập bằng `bob` (userId `U002`) và đặt đơn `amount=250000` → `payment.failed` (U002 chỉ có 100.000 trong ví) → order `FAILED`.
- `items=[{"sku":"ITEM-02","qty":1}]` → `inventory.failed` (ITEM-02 hết hàng sẵn) → order `FAILED` +
  tự động compensate hoàn tiền → kiểm tra sổ cái sẽ thấy đủ cặp `CHARGE_SUCCESS`+`REFUND`. `GET /payments/ledger`
  cần SERVICE token (không phải token người dùng): xin bằng
  `curl -X POST localhost:8090/oauth2/token -d "grant_type=client_credentials&client_id=order-service&client_secret=order-service-secret&scope=ledger:read"`
  hoặc đơn giản gọi `GET /orders/reconciliation` bằng token `admin` - order-service tự xin token để hỏi payment-service.

Mục tiêu chính của bộ code này vẫn là **đọc để hiểu flow và học cách soi lỗi trong hệ thống event-driven**
— phần build/test thật chỉ để chứng minh những gì đọc được là đúng, không phải để deploy production.

---

## 12. Đối chiếu với JD backend điển hình

| Yêu cầu JD | Nằm ở đâu trong repo |
|---|---|
| Redis: caching | `OrderQueryService` (mục 7.1) |
| Redis: idempotency phân tán | `InMemoryPaymentGateway`, `InMemoryInventoryRepository` (mục 7.2) |
| Clean code, design pattern | Saga Choreography, Repository/Gateway pattern, ACL (mục 4); tách interface khỏi implementation (mục 6) |
| Well-tested | JUnit 5 + Mockito + AssertJ, 121 test qua 5 service (mục 9, 13) |
| Authentication/Authorization | JWT RS256 + OAuth2 client credentials + phân quyền theo role/scope/dữ liệu (mục 13) |
| Payment processing | `PaymentGateway`/`PaymentListener`/`RefundListener` |
| Reconciliation | `ReconciliationService` (mục 8.2) |
| Reporting | `ReportService`, `PaymentLedger` (mục 8.1, 8.3) |
| Documentation | README này + OpenAPI/Swagger UI tự sinh (mục 10) |
| SOLID | Mục 6 — S/D thể hiện rõ, O cố tình để lộ 1 trade-off chưa tối ưu để bàn luận |

**Giới hạn còn lại nếu dùng repo này làm bằng chứng phỏng vấn** (nói thẳng để không quá lời): dữ liệu vẫn
toàn bộ in-memory (mất khi restart, không dùng được khi scale ngang thật), chưa có TLS và chưa bảo vệ
RabbitMQ/Redis bằng tài khoản riêng (xem mục 13.5 - danh sách đầy đủ), chưa có integration test với
Redis/RabbitMQ thật (Testcontainers), chưa có CI pipeline. Đây vẫn là 1 bộ demo học tập, không phải
production-ready service.

---

## 13. Xác thực & phân quyền (JWT)

Trước đây các service không xác thực nhau (và không xác thực cả người dùng): ai chạm được cổng là gọi
được. Giờ có `auth-service` phát token và 3 service (order, payment, catalog) bắt buộc phải có token hợp lệ.

### 13.1. Mô hình

```
 Người dùng ──login(username,password)──▶ auth-service ──JWT (sub=U001, roles=[USER])──▶ người dùng
 Người dùng ──Bearer JWT──▶ order-service   (kiểm tra chữ ký bằng KHOÁ CÔNG KHAI lấy từ auth-service/oauth2/jwks)

 order-service ──client_id+secret──▶ auth-service ──JWT (sub=order-service, scope=ledger:read)──▶ order-service
 order-service ──Bearer service JWT──▶ payment-service  (GET /payments/ledger, cần scope ledger:read)

 Client gRPC ──client_id+secret──▶ auth-service ──JWT (scope=catalog:read catalog:write...)──▶ client
 Client gRPC ──metadata "authorization: Bearer ..."──▶ catalog-service (JwtAuthInterceptor)
```

- **RS256 (bất đối xứng)**: chỉ auth-service giữ khoá RIÊNG để ký. Các service khác chỉ có khoá CÔNG KHAI
  để kiểm tra - kiểm tra được nhưng KHÔNG tự phát hành được token giả (khác HS256 dùng chung 1 secret:
  service nào biết secret cũng giả mạo được token của người khác).
- **2 loại token**: token người dùng (`sub` = userId, claim `roles`) và token service (`sub` = tên service,
  claim `scope`). Token service ngắn hạn hơn (5 phút so với 15 phút) và mỗi service chỉ xin được đúng scope
  nó được cấp (đặc quyền tối thiểu).

### 13.2. Ai được làm gì

| Kênh | Cơ chế | Điều kiện |
|---|---|---|
| `POST /orders` | Spring Security resource server | role `USER`; `userId` lấy từ token, **không** còn nhận từ body |
| `GET /orders/{id}/status` | như trên + kiểm tra chủ đơn | chủ đơn hoặc `ADMIN`; đơn của người khác trả **404** (không lộ đơn có tồn tại) |
| `/orders/report/**`, `/orders/reconciliation` | như trên | role `ADMIN` |
| `/payments/ledger/**` | resource server | service token có scope `ledger:read` (token người dùng, kể cả admin, bị 403) |
| gRPC `catalog-service` | `JwtAuthInterceptor` | mỗi RPC cần 1 scope: `catalog:read` / `catalog:write` / `catalog:reserve`; thiếu header -> `UNAUTHENTICATED`, thiếu scope -> `PERMISSION_DENIED` |
| Mọi đường dẫn khác | `denyAll` | chặn mặc định - thêm endpoint mới mà quên khai báo quyền thì bị chặn, không bị mở toang |

Rate limit của gRPC giờ tính **theo từng người gọi** (key = method + `sub` của token), không còn dùng chung
1 hạn mức cho cả hệ thống.

### 13.3. Chạy thử nhanh

```bash
mvn -q -DskipTests package   # trong từng thư mục service, rồi: java -jar target/<service>-1.0.0.jar
curl -s -o /dev/null -w "%{http_code}\n" localhost:8081/orders/x/status          # 401: không token
curl -s -X POST localhost:8090/auth/login -H "Content-Type: application/json" \
     -d '{"username":"alice","password":"alice-pass"}'                           # nhận access_token
# gRPC: chạy CatalogClientDemo (catalog-service/README.md) - mục "0) XÁC THỰC" in ra UNAUTHENTICATED /
# PERMISSION_DENIED / NOT_FOUND (NOT_FOUND nghĩa là đã QUA cổng xác thực, tới được logic nghiệp vụ)
```

### 13.4. Lỗi tìm ra nhờ CHẠY THẬT (unit test không bắt được)

1. **`catalog-service` khởi động xong rồi tự thoát.** Thread của gRPC server là daemon, app lại không có
   web server nào giữ JVM sống - `main()` chạy xong là JVM tắt. Bản trước còn viết comment khẳng định
   ngược lại mà chưa từng chạy thử. Sửa: 1 thread không-daemon chờ `server.awaitTermination()`
   (`GrpcServerLifecycle.keepJvmAlive`).
2. **Comment về thứ tự interceptor bị SAI.** `ServerInterceptors.intercept(svc, a, b, c)` chạy `c` TRƯỚC,
   còn `interceptForward(svc, a, b, c)` mới chạy `a` trước. Đây không phải chuyện thẩm mỹ: rate limit
   muốn tính theo người gọi thì xác thực phải chạy trước nó. Giờ dùng `interceptForward` và có
   `InterceptorOrderTest` khoá cứng cả thư viện lẫn wiring thật.
3. **`denyAll` che mất lỗi 500 thành 403.** Khi controller ném lỗi (ở đây: Redis không chạy), Spring
   chuyển sang `/error`, mà `/error` cũng bị chặn -> client thấy "cấm truy cập" thay vì lỗi server thật.
   Sửa: cho phép riêng `/error`. Unit test bằng MockMvc không có bước chuyển tiếp này nên không bắt được.

### 13.5. Chưa làm — nói thẳng để không ngộ nhận là "đủ an toàn cho production"

- **Chưa có TLS ở bất kỳ kênh nào.** JWT đi trên mạng dạng chữ thường: ai nghe lén được là lấy được token
  và dùng lại tới khi hết hạn. Xác thực bằng token chỉ có ý nghĩa thật khi đi kèm TLS/mTLS.
- **RabbitMQ vẫn `guest/guest`, Redis không mật khẩu, dùng chung cho mọi service.** Luồng Saga qua RabbitMQ
  KHÔNG được bảo vệ bởi JWT (không phải HTTP): ai có tài khoản broker vẫn publish được `payment.success` giả.
- **`amount` do client gửi lên** (`POST /orders`) - server tin giá do khách tự khai. Hệ thống thật phải tự
  tính tổng tiền từ giá sản phẩm phía server.
- Swagger UI và gRPC Reflection đang mở (tiện học, production nên tắt/giới hạn). `rest-vs-grpc-demo` và
  `inventory-service` (chỉ có message, chưa có REST) chưa được đụng tới.
- `/auth/login` chưa chống dò mật khẩu (khoá tạm sau N lần sai, rate limit), chưa có refresh token, thu hồi
  token, xoay khoá có kế hoạch, hay kiểm tra `aud` (token cấp cho service A vẫn đưa được cho service B nếu
  đủ scope). Khoá ký sinh mới mỗi lần auth-service khởi động nên restart là mọi token cũ mất hiệu lực.
- Tài khoản/secret demo nằm thẳng trong `application.yml`; production dùng DB (chỉ lưu hash) + vault.
- Luồng đặt đơn thật và kiểm tra "chủ đơn" mới được chứng minh bằng unit test (`OrderSecurityTest`), chưa
  chạy end-to-end thật vì cần RabbitMQ + Redis (môi trường phát triển hiện tại không có Docker).
