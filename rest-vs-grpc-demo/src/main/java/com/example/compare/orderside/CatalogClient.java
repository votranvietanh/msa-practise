package com.example.compare.orderside;

import java.util.List;

/**
 * "Cổng" Order Service dùng để hỏi Catalog Service. OrderQuoteService chỉ biết interface
 * này, KHÔNG biết bên dưới là REST hay gRPC -> đổi giao thức không phải sửa code nghiệp vụ.
 *
 * Hợp đồng lỗi (giống nhau cho cả 2 implementation - mỗi bên tự dịch từ lỗi giao thức):
 *   - ProductNotFoundException    : có SKU không tồn tại (REST 404 / gRPC NOT_FOUND)
 *   - CatalogUnavailableException : quá thời gian chờ / không kết nối được
 *                                   (REST ResourceAccessException / gRPC DEADLINE_EXCEEDED, UNAVAILABLE)
 */
public interface CatalogClient {

    List<ProductQuote> getProducts(List<String> skus);
}
