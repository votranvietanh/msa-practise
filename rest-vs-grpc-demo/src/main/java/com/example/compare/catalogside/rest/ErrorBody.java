package com.example.compare.catalogside.rest;

/**
 * Format lỗi JSON do team TỰ QUY ƯỚC - mỗi team/công ty một kiểu (có nơi dùng RFC 7807
 * ProblemDetail, có nơi tự chế). gRPC thì có sẵn bộ status code chuẩn + description.
 */
public record ErrorBody(String code, String message) {
}
