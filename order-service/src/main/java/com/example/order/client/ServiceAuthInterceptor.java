package com.example.order.client;

import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Tự đính "Authorization: Bearer <token>" vào MỌI request đi ra của RestClient mà nó gắn
 * vào - code gọi nghiệp vụ (RestPaymentLedgerClient) không phải nhớ xử lý token.
 *
 * Gặp 401 (nơi nhận từ chối token - ví dụ auth-service vừa restart và đổi khoá ký nên token
 * đang cache không còn hợp lệ) thì bỏ token cũ, xin token mới và thử lại ĐÚNG 1 LẦN. Không
 * thử lại nhiều lần để tránh vòng lặp vô tận khi sai cấu hình thật (secret sai, thiếu scope).
 */
public class ServiceAuthInterceptor implements ClientHttpRequestInterceptor {

    private final ServiceTokenProvider tokenProvider;

    public ServiceAuthInterceptor(ServiceTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().setBearerAuth(tokenProvider.getToken());
        ClientHttpResponse response = execution.execute(request, body);

        if (response.getStatusCode() == HttpStatus.UNAUTHORIZED) {
            response.close();
            tokenProvider.invalidate();
            request.getHeaders().setBearerAuth(tokenProvider.getToken());
            return execution.execute(request, body);
        }
        return response;
    }
}
