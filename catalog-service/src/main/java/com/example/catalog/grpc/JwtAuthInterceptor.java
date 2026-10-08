package com.example.catalog.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cổng bảo vệ của gRPC server - làm đúng việc mà BearerTokenAuthenticationFilter + luật
 * hasAuthority(...) của Spring Security làm bên REST (order-service/payment-service):
 *   1. XÁC THỰC (authentication): lấy JWT từ metadata "authorization", kiểm tra chữ ký/hạn/issuer
 *      -> biết "người gọi là ai". Sai/thiếu -> UNAUTHENTICATED (tương đương HTTP 401).
 *   2. PHÂN QUYỀN (authorization): người gọi có scope cần thiết cho RPC này không
 *      -> không có -> PERMISSION_DENIED (tương đương HTTP 403).
 *
 * Phải đứng TRƯỚC RateLimitInterceptor trong chuỗi (xem GrpcServerLifecycle) để rate limit biết
 * người gọi là ai mà tính hạn mức riêng cho từng người.
 */
@Component
public class JwtAuthInterceptor implements ServerInterceptor {

    /** Header gRPC là "metadata" (cặp key-value, giống HTTP header); key luôn viết thường. */
    static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * RPC nào cần scope nào. Dùng hằng số do protoc sinh ra (getCreateProductMethod()...) thay vì
     * gõ tay chuỗi "catalog.CatalogService/CreateProduct": đổi tên rpc trong .proto thì chỗ này báo
     * lỗi biên dịch ngay, thay vì âm thầm hỏng quyền lúc chạy.
     *
     * RPC nào KHÔNG có trong bảng này bị từ chối (deny by default) - thêm rpc mới vào .proto mà
     * quên khai báo quyền thì bị chặn, không vô tình mở cho mọi token hợp lệ (có test canh việc này).
     */
    private static final Map<String, String> REQUIRED_SCOPE = Map.of(
            CatalogServiceGrpc.getCreateProductMethod().getFullMethodName(), "catalog:write",
            CatalogServiceGrpc.getBulkRestockMethod().getFullMethodName(), "catalog:write",
            CatalogServiceGrpc.getGetProductMethod().getFullMethodName(), "catalog:read",
            CatalogServiceGrpc.getCheckStockMethod().getFullMethodName(), "catalog:read",
            CatalogServiceGrpc.getGetTopSellersMethod().getFullMethodName(), "catalog:read",
            CatalogServiceGrpc.getWatchProductMethod().getFullMethodName(), "catalog:read",
            CatalogServiceGrpc.getReserveStockSessionMethod().getFullMethodName(), "catalog:reserve");

    private final JwtDecoder jwtDecoder;

    public JwtAuthInterceptor(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    static Optional<String> requiredScopeFor(String fullMethodName) {
        return Optional.ofNullable(REQUIRED_SCOPE.get(fullMethodName));
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String header = headers.get(AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return reject(call, Status.UNAUTHENTICATED.withDescription("Thiếu header authorization dạng 'Bearer <token>'"));
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(header.substring(BEARER_PREFIX.length()).trim());
        } catch (BadJwtException e) {
            // Mô tả trả cho client CỐ Ý chung chung: nói rõ "hết hạn" hay "sai chữ ký" hay "sai
            // issuer" giúp kẻ tấn công dò hệ thống. Chi tiết cho dev chỉ ghi ở log phía server.
            System.err.println("[JwtAuth] " + method(call) + " bị từ chối: " + e.getMessage());
            return reject(call, Status.UNAUTHENTICATED.withDescription("Token không hợp lệ hoặc đã hết hạn"));
        } catch (JwtException e) {
            // Còn lại là lỗi PHÍA HẠ TẦNG (vd không tải được khoá từ auth-service), không phải lỗi
            // của client -> UNAVAILABLE để client biết có thể thử lại sau, đừng báo nhầm token sai.
            System.err.println("[JwtAuth] không xác minh được token: " + e.getMessage());
            return reject(call, Status.UNAVAILABLE.withDescription("Chưa xác minh được token lúc này, thử lại sau"));
        }

        String method = method(call);
        Set<String> scopes = scopesOf(jwt);

        Optional<String> required = requiredScopeFor(method);
        if (required.isEmpty() || !scopes.contains(required.get())) {
            return reject(call, Status.PERMISSION_DENIED.withDescription(
                    required.map(s -> "Thiếu scope '" + s + "'").orElse("RPC chưa được khai báo quyền")));
        }

        Context context = Context.current()
                .withValue(CallerContext.CALLER_ID, jwt.getSubject())
                .withValue(CallerContext.CALLER_SCOPES, scopes);

        // Contexts.interceptCall: chạy phần xử lý tiếp theo (và cả các callback sau này của stream)
        // BÊN TRONG context này, nhờ vậy CallerContext.CALLER_ID.get() hoạt động ở service và
        // các interceptor phía sau.
        return Contexts.interceptCall(context, call, headers, next);
    }

    private static String method(ServerCall<?, ?> call) {
        return call.getMethodDescriptor().getFullMethodName();
    }

    private static Set<String> scopesOf(Jwt jwt) {
        String scope = jwt.getClaimAsString("scope");
        if (scope == null || scope.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(scope.trim().split("\\s+")));
    }

    /** Đóng RPC ngay với lỗi, trả listener rỗng để bỏ qua mọi dữ liệu client còn gửi tới sau đó. */
    private static <ReqT, RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
