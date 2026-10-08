package com.example.catalog.grpc;

import io.grpc.Context;

import java.util.Set;

/**
 * "Người gọi" của RPC đang chạy - JwtAuthInterceptor ghi vào đây sau khi xác thực, các
 * interceptor/service phía sau đọc ra mà không phải tự parse lại token.
 *
 * io.grpc.Context là cơ chế truyền dữ liệu theo từng lời gọi RPC (gần giống ThreadLocal nhưng
 * đi theo RPC chứ không theo thread, nên vẫn đúng khi xử lý chuyển qua thread khác). Đây là
 * chỗ tương đương SecurityContextHolder của Spring Security bên REST.
 *
 * .get() trả null nếu chưa có người gọi nào được xác thực (vd interceptor chạy TRƯỚC JwtAuthInterceptor).
 */
public final class CallerContext {
    private CallerContext() {}

    public static final Context.Key<String> CALLER_ID = Context.key("caller-id");
    public static final Context.Key<Set<String>> CALLER_SCOPES = Context.key("caller-scopes");
}
