package com.example.auth.token;

import com.example.auth.config.AuthProperties;
import com.nimbusds.jose.jwk.RSAKey;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TokenService {

    private record StoredUser(String passwordHash, String userId, List<String> roles) {}
    private record StoredClient(String secretHash, List<String> scopes) {}

    private final AuthProperties props;
    private final JwtEncoder jwtEncoder;
    private final PasswordEncoder passwordEncoder;
    private final String keyId;

    private final Map<String, StoredUser> users;
    private final Map<String, StoredClient> clients;
    private final String dummyHash;

    public TokenService(AuthProperties props, JwtEncoder jwtEncoder,
                         PasswordEncoder passwordEncoder, RSAKey rsaSigningKey) {
        this.props = props;
        this.jwtEncoder = jwtEncoder;
        this.passwordEncoder = passwordEncoder;
        this.keyId = rsaSigningKey.getKeyID();

        // Băm mật khẩu/secret MỘT LẦN lúc khởi động, từ đó so khớp luôn qua hash - đúng
        // cách code đọc từ DB thật (DB chỉ giữ hash). Bản gốc plaintext trong application.yml
        // chỉ là dữ liệu mồi của demo.
        this.users = props.users().stream().collect(Collectors.toMap(
                AuthProperties.UserAccount::username,
                u -> new StoredUser(passwordEncoder.encode(u.password()), u.userId(), u.roles())));
        this.clients = props.clients().stream().collect(Collectors.toMap(
                AuthProperties.ServiceClient::clientId,
                c -> new StoredClient(passwordEncoder.encode(c.clientSecret()), c.scopes())));
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** Đăng nhập người dùng: trả JWT có sub = userId nội bộ và claim "roles". */
    public IssuedToken loginUser(String username, String password) {
        StoredUser user = users.get(username);

        // Username không tồn tại vẫn phải tốn đúng 1 lần so khớp BCrypt (với hash giả) -
        // nếu trả lỗi ngay thì "user không tồn tại" nhanh hơn "sai mật khẩu" vài trăm ms,
        // kẻ tấn công đo thời gian là dò ra được tài khoản nào có thật (user enumeration).
        String hash = user != null ? user.passwordHash() : dummyHash;
        boolean passwordMatches = passwordEncoder.matches(password == null ? "" : password, hash);

        if (user == null || !passwordMatches) {
            // Cố ý dùng CÙNG 1 thông báo cho cả 2 trường hợp (không tiết lộ cái nào sai).
            throw new TokenRequestException("invalid_credentials", HttpStatus.UNAUTHORIZED);
        }

        Duration ttl = props.userTokenTtl();
        String token = sign(user.userId(), ttl, Map.of("roles", user.roles()));
        return new IssuedToken(token, ttl.toSeconds(), null);
    }

    /** OAuth2 client credentials: service xin token cho CHÍNH NÓ (không đại diện người dùng nào). */
    public IssuedToken issueServiceToken(String clientId, String clientSecret, String requestedScope) {
        StoredClient client = clients.get(clientId);

        String hash = client != null ? client.secretHash() : dummyHash;
        boolean secretMatches = passwordEncoder.matches(clientSecret == null ? "" : clientSecret, hash);

        if (client == null || !secretMatches) {
            throw new TokenRequestException("invalid_client", HttpStatus.UNAUTHORIZED);
        }

        Set<String> granted = resolveScopes(client, requestedScope);
        String scopeClaim = String.join(" ", granted);

        Duration ttl = props.serviceTokenTtl();
        String token = sign(clientId, ttl, Map.of("scope", scopeClaim));
        return new IssuedToken(token, ttl.toSeconds(), scopeClaim);
    }

    /**
     * Không yêu cầu scope cụ thể -> cấp đủ scope client được phép. Có yêu cầu -> chỉ cấp được
     * tập con của những gì client được phép (đặc quyền tối thiểu: xin ít hơn thì nhận ít hơn),
     * xin thừa là lỗi invalid_scope chứ không âm thầm cắt bớt.
     */
    private Set<String> resolveScopes(StoredClient client, String requestedScope) {
        if (requestedScope == null || requestedScope.isBlank()) {
            return new LinkedHashSet<>(client.scopes());
        }
        Set<String> requested = Arrays.stream(requestedScope.trim().split("\\s+"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!client.scopes().containsAll(requested)) {
            throw new TokenRequestException("invalid_scope", HttpStatus.BAD_REQUEST);
        }
        return requested;
    }

    private String sign(String subject, Duration ttl, Map<String, Object> extraClaims) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(props.issuer())
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString());
        extraClaims.forEach(claims::claim);

        // keyId (kid) trong header cho phép bên kiểm tra chọn đúng khoá công khai trong JWKS,
        // nền tảng để xoay khoá mà không làm sập hệ thống.
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
