package com.example.auth.web;

import com.example.auth.token.IssuedToken;
import com.example.auth.token.TokenRequestException;
import com.example.auth.token.TokenService;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AuthController {

    private final TokenService tokenService;
    private final RSAKey rsaSigningKey;

    public AuthController(TokenService tokenService, RSAKey rsaSigningKey) {
        this.tokenService = tokenService;
        this.rsaSigningKey = rsaSigningKey;
    }

    /** Người dùng đăng nhập bằng username/password -> nhận JWT dùng gọi order-service. */
    @PostMapping(value = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public TokenResponse login(@RequestBody LoginRequest request) {
        return toResponse(tokenService.loginUser(request.username(), request.password()));
    }

    /**
     * OAuth2 client credentials (RFC 6749 mục 4.4): SERVICE xin token cho chính nó, gửi dạng
     * form-urlencoded (không phải JSON) đúng chuẩn để thư viện OAuth2 client nào cũng gọi được.
     */
    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public TokenResponse token(@RequestParam("grant_type") String grantType,
                                @RequestParam("client_id") String clientId,
                                @RequestParam("client_secret") String clientSecret,
                                @RequestParam(value = "scope", required = false) String scope) {
        if (!"client_credentials".equals(grantType)) {
            throw new TokenRequestException("unsupported_grant_type", HttpStatus.BAD_REQUEST);
        }
        return toResponse(tokenService.issueServiceToken(clientId, clientSecret, scope));
    }

    /**
     * JWKS (JSON Web Key Set): danh sách khoá CÔNG KHAI để các service khác kiểm tra chữ ký.
     * toPublicJWK() cắt bỏ phần khoá riêng - gọi nhầm toJSONObject() trên khoá đầy đủ là
     * lộ khoá ký token cho cả thế giới, 1 lỗi cực nghiêm trọng và rất dễ mắc.
     */
    @GetMapping("/oauth2/jwks")
    public Map<String, Object> jwks() {
        return new JWKSet(rsaSigningKey.toPublicJWK()).toJSONObject();
    }

    @ExceptionHandler(TokenRequestException.class)
    public ResponseEntity<Map<String, String>> handle(TokenRequestException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getError()));
    }

    private TokenResponse toResponse(IssuedToken token) {
        return new TokenResponse(token.accessToken(), "Bearer", token.expiresInSeconds(), token.scope());
    }
}
