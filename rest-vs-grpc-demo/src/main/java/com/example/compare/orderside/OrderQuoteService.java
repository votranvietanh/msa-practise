package com.example.compare.orderside;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Nghiệp vụ của Order Service: báo giá đơn hàng (tính tổng tiền + kiểm tra tồn kho) trước
 * khi thực sự tạo đơn. Class này GIỐNG HỆT NHAU dù chạy với REST hay gRPC - bằng chứng là
 * nó không import bất kỳ thứ gì của Spring Web, RestClient, io.grpc hay protobuf.
 *
 * Không đánh @Service vì cần 2 instance (1 cho mỗi giao thức) - xem CatalogClientConfig.
 */
public class OrderQuoteService {

    private final CatalogClient catalogClient;

    public OrderQuoteService(CatalogClient catalogClient) {
        this.catalogClient = catalogClient;
    }

    /** @param items sku -> số lượng muốn mua */
    public OrderQuote quote(Map<String, Integer> items) {
        // GỌI 1 LẦN cho tất cả SKU (batch) thay vì N lần cho N SKU - quy tắc quan trọng khi
        // giao tiếp giữa microservice, dù REST hay gRPC: mỗi lần gọi mạng đều tốn độ trễ.
        List<ProductQuote> products = catalogClient.getProducts(List.copyOf(items.keySet()));

        List<OrderQuote.Line> lines = new ArrayList<>();
        long total = 0;
        for (ProductQuote p : products) {
            int qty = items.get(p.sku());
            if (p.stock() < qty) {
                throw new OutOfStockException(p.sku() + " chỉ còn " + p.stock() + ", không đủ " + qty);
            }
            long lineTotal = p.price() * qty;
            lines.add(new OrderQuote.Line(p.sku(), p.name(), qty, p.price(), lineTotal));
            total += lineTotal;
        }
        return new OrderQuote(lines, total);
    }
}
