# REST vs gRPC: cùng một case, hai cách giao tiếp

Module này **độc lập**: không cần Redis hay RabbitMQ, không tham gia Saga. Mục đích duy nhất là đặt REST và gRPC cạnh nhau trong **cùng một bài toán thực tế** để thấy chúng khác nhau ở đâu.

## 1. Bài toán

Khi khách bấm "Đặt hàng", **Order Service** phải hỏi **Catalog Service** giá và tồn kho của các SKU trong giỏ, sau đó tính tổng tiền và từ chối nếu hết hàng. Đây là một lời gọi **đồng bộ** (Order phải đợi câu trả lời mới làm tiếp được), rất điển hình cho giao tiếp service-to-service.

```
                         ┌──────────── Catalog Service ─────────────┐
                         │                                           │
 Order Service  ──REST──▶│ CatalogRestController   (HTTP/1.1, JSON)  │
 OrderQuoteService       │          │                                │
   └ CatalogClient       │          ▼                                │
      ├ RestCatalogClient│    ProductCatalog  (logic dùng chung)     │
      └ GrpcCatalogClient│          ▲                                │
                ──gRPC──▶│ ProductQueryGrpcService (HTTP/2, protobuf)│
                         └───────────────────────────────────────────┘
```

Cả hai service được gói chung trong một process cho dễ chạy, nhưng giao tiếp **qua mạng thật** (localhost). Package `orderside` không import bất cứ thứ gì từ `catalogside`.

## 2. Đọc code theo cặp (trái: REST, phải: gRPC)

| Vai trò | REST | gRPC |
|---|---|---|
| Hợp đồng API | Rải rác trong annotation: URL, method, tên field JSON | Một file duy nhất: [product_query.proto](src/main/proto/product_query.proto) |
| DTO phía server | [ProductResponse.java](src/main/java/com/example/compare/catalogside/rest/ProductResponse.java), viết tay | `Product` **sinh tự động** từ .proto |
| DTO phía client | `ProductJson` trong [RestCatalogClient](src/main/java/com/example/compare/orderside/rest/RestCatalogClient.java), **viết tay lần 2** | Dùng lại chính `Product` đã sinh |
| Server endpoint | [CatalogRestController](src/main/java/com/example/compare/catalogside/rest/CatalogRestController.java) | [ProductQueryGrpcService](src/main/java/com/example/compare/catalogside/grpc/ProductQueryGrpcService.java) |
| Khởi động server | Spring Boot tự mở Tomcat | Tự viết [GrpcServerLifecycle](src/main/java/com/example/compare/catalogside/grpc/GrpcServerLifecycle.java) |
| Client | [RestCatalogClient](src/main/java/com/example/compare/orderside/rest/RestCatalogClient.java) | [GrpcCatalogClient](src/main/java/com/example/compare/orderside/grpc/GrpcCatalogClient.java) |
| Nghiệp vụ | [OrderQuoteService](src/main/java/com/example/compare/orderside/OrderQuoteService.java), **dùng chung**, không biết giao thức bên dưới | ← cùng một class |

Cách đọc gợi ý: mở `RestCatalogClient` và `GrpcCatalogClient` cạnh nhau. Các comment đánh số (1), (2), (3) trỏ đúng vào những chỗ khác nhau giữa hai bên.

## 3. Chạy và quan sát

```bash
mvn test
```

Đọc các dòng `[SO SÁNH]` trong console. Mỗi test trong [RestVsGrpcComparisonTest](src/test/java/com/example/compare/RestVsGrpcComparisonTest.java) chạy **hai lần**, một lần với REST và một lần với gRPC, dùng cùng một assertion.

Hoặc chạy app rồi thử tay:

```bash
mvn spring-boot:run
```

```bash
curl "http://localhost:8090/api/v1/orders/quote?via=rest&items=SHIRT-01:2,JEAN-01:1"
```

```bash
curl "http://localhost:8090/api/v1/orders/quote?via=grpc&items=SHIRT-01:2,JEAN-01:1"
```

Dữ liệu mẫu: `SHIRT-01` (còn 20), `JEAN-01` (còn 5), `CAP-01` (hết hàng), `SLOW-01` (server cố tình trả lời chậm 2 giây).

## 4. Follow-up: từng khác biệt, kèm kết quả đo thật

### 4.1 Kết quả nghiệp vụ: **giống hệt nhau**

```
REST happy path -> OrderQuote[... total=750000]
gRPC happy path -> OrderQuote[... total=750000]
```

Đây là bài học đầu tiên và quan trọng nhất: **giao thức chỉ là lớp vỏ**. Nhờ interface `CatalogClient`, chuyển từ REST sang gRPC không phải sửa dòng nào trong `OrderQuoteService`.

### 4.2 Hợp đồng API: chuỗi quy ước so với file .proto

Ở REST, client phải tự làm ba việc:
1. Gõ chuỗi `"/api/v1/products"`. Gõ sai thì **lúc chạy** mới nhận 404.
2. Tự viết lại DTO `ProductJson` sao cho khớp tên field với server.
3. Tự parse body lỗi theo format mà team tự quy ước.

Ở gRPC, client gọi `stub.getProducts(request)`. Gõ sai tên method thì **lỗi compile** ngay, và mọi class đều sinh từ cùng một file `.proto` mà server đang dùng.

### 4.3 Nâng cấp API: đây là khác biệt "đắt giá" nhất

Test [ContractDriftTest](src/test/java/com/example/compare/orderside/rest/ContractDriftTest.java) giả lập tình huống team Catalog đổi tên `price` thành `unitPrice` và thêm field `discount`, trong khi Order Service vẫn chạy bản client cũ:

```
REST client cũ đọc JSON v2 -> ProductJson[sku=SHIRT-01, ..., price=0, stock=20]   <-- price = 0 ÂM THẦM
gRPC client cũ đọc bytes v2 -> price = 150000, field 5 (discount) được giữ nguyên dạng unknown field
```

- **REST/JSON** khớp field theo **tên**. Đổi tên thì client nhận giá 0đ mà **không có exception nào**. Lỗi kiểu này thường chỉ lộ ra khi kế toán đối soát.
- **gRPC/protobuf** khớp field theo **số** (`= 3`). Tên field không hề được gửi đi trên dây, nên đổi tên vẫn tương thích. Field mới mà client cũ chưa biết sẽ được giữ lại chứ không làm hỏng dữ liệu.

Quy tắc đi kèm: trong `.proto`, **không bao giờ đổi hoặc tái sử dụng số field**. Khi xoá một field, hãy đánh dấu `reserved 3;`.

### 4.4 Xử lý lỗi: HTTP status + body tự quy ước so với Status code chuẩn

| Tình huống | REST trả về | gRPC trả về | Client dịch thành |
|---|---|---|---|
| SKU không tồn tại | `404` + `{"code":"PRODUCT_NOT_FOUND",...}` | `NOT_FOUND` + description | `ProductNotFoundException` |
| Server chậm quá timeout | `ResourceAccessException: Read timed out` | `DEADLINE_EXCEEDED` | `CatalogUnavailableException` |
| Hết hàng | *(lỗi nghiệp vụ phía Order, không liên quan giao thức)* | ← giống bên trái | `OutOfStockException` |

Với REST, format body lỗi mỗi công ty một kiểu, nên client phải biết trước để parse. Với gRPC, bộ khoảng 16 status code là chuẩn chung cho mọi ngôn ngữ.

### 4.5 Timeout so với Deadline

Cả hai bên đều bỏ cuộc sau khoảng 1 giây, dù server cần 2 giây:

```
REST timeout -> ... I/O error on GET request ...: Read timed out
gRPC timeout -> DEADLINE_EXCEEDED: CallOptions deadline exceeded after 0.999512400s
```

Điểm khác nhau:
- **REST:** timeout nằm ở tầng socket phía client và **server không hề biết** client đã bỏ đi, nên vẫn tiếp tục làm hết 2 giây. Nếu quên cấu hình timeout, mặc định là **chờ vô hạn**.
- **gRPC:** deadline được **gửi kèm sang server** qua header `grpc-timeout`, nên server có thể biết và dừng sớm. Khi Catalog gọi tiếp sang service khác, deadline còn lại có thể được truyền tiếp dọc chuỗi gọi. Bẫy cần tránh: deadline là mốc thời gian **tuyệt đối**, nên phải gắn `withDeadlineAfter(...)` **cho mỗi lần gọi**, không gắn một lần vào stub.

### 4.6 Kích thước dữ liệu

```
100 sản phẩm: JSON = 7614 bytes, Protobuf = 4092 bytes (nhỏ hơn 46%)
```

Protobuf không gửi tên field (`"price":`) mà chỉ gửi số field kèm giá trị nhị phân. Payload càng nhiều field số, càng lặp lại, thì chênh lệch càng lớn. Đổi lại, protobuf **không đọc được bằng mắt**: không thể mở tab Network hay dùng `curl` để xem như JSON, mà phải dùng `grpcurl` hoặc Postman.

### 4.7 Tốc độ: đừng tin vào con số trên localhost

Đo thật qua `elapsedMs` của API quote:

```
Lần 1 (cold): rest 40ms, grpc 124ms   <- gRPC tạo kết nối HTTP/2 lazy ở lần gọi đầu
Lần 2-4     : rest 1-2ms, grpc 2-3ms  <- sau khi "khởi động nóng", gần như bằng nhau
```

Trên localhost, với payload nhỏ, **gRPC không nhanh hơn**. Lợi thế về tốc độ của gRPC chỉ lộ ra khi:
- lưu lượng lớn và liên tục: một kết nối HTTP/2 được dùng lại, nhiều request chạy song song trên đó (multiplexing), không phải mở kết nối mới;
- payload lớn: tiết kiệm băng thông và giảm thời gian parse JSON;
- mạng thật có độ trễ giữa các node.

Muốn so sánh hiệu năng nghiêm túc thì phải benchmark bằng công cụ như `ghz` hoặc `wrk` trên môi trường giống production, không kết luận từ một vài lần `curl`.

### 4.8 Chi phí vận hành

| | REST | gRPC |
|---|---|---|
| Setup | `spring-boot-starter-web` là đủ | Thêm 4 dependency, plugin sinh code, tự start server |
| Test tay | `curl`, trình duyệt | `grpcurl` (cần bật reflection hoặc có file .proto) |
| Gọi từ trình duyệt | Được | Không gọi trực tiếp được (cần gRPC-Web + proxy) |
| Chia sẻ hợp đồng | Swagger (tuỳ chọn) | Phải phân phối file `.proto` cho mọi team client |

## 5. Kết luận: khi nào chọn cái nào?

- **Chọn REST** cho API ra bên ngoài (frontend, mobile, đối tác), cho CRUD đơn giản, và khi team chưa quen gRPC.
- **Chọn gRPC** cho giao tiếp nội bộ giữa các service khi cần một hợp đồng chặt chẽ giữa nhiều team, lưu lượng cao, hoặc cần streaming (xem 4 kiểu RPC ở [catalog-service](../catalog-service/README.md)).
- Mô hình phổ biến: **REST ở phía ngoài, gRPC ở bên trong**. Chính Order Service trong demo này cũng như vậy: nhận REST từ người dùng (`/api/v1/orders/quote`) và gọi gRPC sang Catalog.
- Với các bước **không cần câu trả lời ngay** (thanh toán, trừ kho trong Saga), cả REST lẫn gRPC đều không phải lựa chọn tốt nhất. Hãy dùng message queue (RabbitMQ), như 3 service chính của repo này.
