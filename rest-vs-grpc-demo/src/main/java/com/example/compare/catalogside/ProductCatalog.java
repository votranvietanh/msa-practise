package com.example.compare.catalogside;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Logic nghiệp vụ DÙNG CHUNG cho cả REST lẫn gRPC. Điểm mấu chốt của cả bài so sánh:
 * REST hay gRPC chỉ là "lớp vỏ giao thức" bọc ngoài, phần lõi nghiệp vụ không đổi 1 dòng.
 */
@Service
public class ProductCatalog {

    /** SKU đặc biệt: server cố tình xử lý chậm để minh hoạ timeout (REST) vs deadline (gRPC). */
    public static final String SLOW_SKU = "SLOW-01";
    private static final long SLOW_DELAY_MS = 2_000;

    private final Map<String, ProductInfo> products = Map.of(
            "SHIRT-01", new ProductInfo("SHIRT-01", "Áo thun basic", 150_000, 20),
            "JEAN-01", new ProductInfo("JEAN-01", "Quần jean slim", 450_000, 5),
            "CAP-01", new ProductInfo("CAP-01", "Mũ lưỡi trai", 90_000, 0),
            SLOW_SKU, new ProductInfo(SLOW_SKU, "Hàng từ kho chậm", 10_000, 100));

    public List<ProductInfo> findAll(List<String> skus) {
        List<ProductInfo> result = new ArrayList<>();
        for (String sku : skus) {
            ProductInfo product = products.get(sku);
            if (product == null) {
                throw new UnknownSkuException(sku);
            }
            if (SLOW_SKU.equals(sku)) {
                simulateSlowDatabase();
            }
            result.add(product);
        }
        return result;
    }

    private static void simulateSlowDatabase() {
        try {
            Thread.sleep(SLOW_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
