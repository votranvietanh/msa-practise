package com.example.compare.orderside;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API cho người dùng thử tay, chọn giao thức Order -> Catalog bằng tham số `via`:
 *   curl "http://localhost:8090/api/v1/orders/quote?via=rest&items=SHIRT-01:2,JEAN-01:1"
 *   curl "http://localhost:8090/api/v1/orders/quote?via=grpc&items=SHIRT-01:2,JEAN-01:1"
 * Cùng input -> cùng output, chỉ khác "đường ống" bên dưới (và elapsedMs).
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderQuoteController {

    public record QuoteResult(String via, long elapsedMs, OrderQuote quote) {
    }

    private final OrderQuoteService restOrderQuoteService;
    private final OrderQuoteService grpcOrderQuoteService;

    public OrderQuoteController(@Qualifier("restOrderQuoteService") OrderQuoteService restOrderQuoteService,
                                @Qualifier("grpcOrderQuoteService") OrderQuoteService grpcOrderQuoteService) {
        this.restOrderQuoteService = restOrderQuoteService;
        this.grpcOrderQuoteService = grpcOrderQuoteService;
    }

    @GetMapping("/quote")
    public QuoteResult quote(@RequestParam(defaultValue = "grpc") String via, @RequestParam String items) {
        OrderQuoteService service = "rest".equalsIgnoreCase(via) ? restOrderQuoteService : grpcOrderQuoteService;

        long start = System.nanoTime();
        OrderQuote quote = service.quote(parseItems(items));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        return new QuoteResult(via.toLowerCase(), elapsedMs, quote);
    }

    /** "SHIRT-01:2,JEAN-01:1" -> {SHIRT-01=2, JEAN-01=1} */
    private static Map<String, Integer> parseItems(String items) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String item : items.split(",")) {
            String[] parts = item.split(":");
            result.put(parts[0].trim(), parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1);
        }
        return result;
    }

    // Lỗi đã được CatalogClient dịch về cùng 1 bộ exception -> controller không cần biết
    // lỗi gốc là HTTP 404 hay gRPC NOT_FOUND.
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(ProductNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(OutOfStockException.class)
    public ResponseEntity<Map<String, String>> outOfStock(OutOfStockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CatalogUnavailableException.class)
    public ResponseEntity<Map<String, String>> unavailable(CatalogUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }
}
