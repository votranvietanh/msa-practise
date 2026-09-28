package com.example.compare.catalogside;

/**
 * Lỗi nghiệp vụ phía server. Mỗi "cửa" tự dịch lỗi này sang ngôn ngữ của giao thức mình:
 * REST -> HTTP 404 + JSON body, gRPC -> Status.NOT_FOUND.
 */
public class UnknownSkuException extends RuntimeException {

    public UnknownSkuException(String sku) {
        super("Không tìm thấy sản phẩm " + sku);
    }
}
