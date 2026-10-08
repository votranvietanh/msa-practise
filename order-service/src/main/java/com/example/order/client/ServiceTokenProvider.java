package com.example.order.client;

/**
 * "Cho tôi token để gọi service khác" - tách thành interface để ServiceAuthInterceptor
 * không phụ thuộc cách lấy token cụ thể (hiện là OAuth2 client credentials tới auth-service).
 */
public interface ServiceTokenProvider {

    /** Token còn hạn dùng; tự xin token mới nếu chưa có hoặc sắp hết hạn. */
    String getToken();

    /** Bỏ token đang giữ - gọi khi nơi nhận báo token không hợp lệ (vd auth-service đã đổi khoá). */
    void invalidate();
}
