package com.example.auth.token;

import org.springframework.http.HttpStatus;

/** Lỗi khi xin token - mang theo mã lỗi kiểu OAuth2 ("invalid_client", "invalid_scope"...) và HTTP status tương ứng. */
public class TokenRequestException extends RuntimeException {

    private final String error;
    private final HttpStatus status;

    public TokenRequestException(String error, HttpStatus status) {
        super(error);
        this.error = error;
        this.status = status;
    }

    public String getError() { return error; }
    public HttpStatus getStatus() { return status; }
}
