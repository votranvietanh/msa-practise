package com.example.compare.catalogside.rest;

import com.example.compare.catalogside.ProductCatalog;
import com.example.compare.catalogside.UnknownSkuException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * "Cửa REST" của Catalog Service. Hợp đồng API được thể hiện qua: URL + HTTP method +
 * query param + tên field JSON + HTTP status code - tất cả là chuỗi/quy ước, rải rác
 * trong annotation, không có 1 file định nghĩa tập trung như .proto.
 *
 * Thử tay: curl "http://localhost:8090/api/v1/products?skus=SHIRT-01,JEAN-01"
 */
@RestController
@RequestMapping("/api/v1/products")
public class CatalogRestController {

    private final ProductCatalog catalog;

    public CatalogRestController(ProductCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<ProductResponse> getProducts(@RequestParam List<String> skus) {
        return catalog.findAll(skus).stream()
                .map(p -> new ProductResponse(p.sku(), p.name(), p.price(), p.stock()))
                .toList();
    }

    // Dịch lỗi nghiệp vụ -> HTTP 404 + JSON body theo format tự quy ước (ErrorBody).
    @ExceptionHandler(UnknownSkuException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorBody handleUnknownSku(UnknownSkuException e) {
        return new ErrorBody("PRODUCT_NOT_FOUND", e.getMessage());
    }
}
