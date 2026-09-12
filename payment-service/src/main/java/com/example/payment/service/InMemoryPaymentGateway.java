package com.example.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Giả lập cổng thanh toán + ví người dùng (in-memory).
 * Business rule demo: user nào có balance < amount thì charge FAIL.
 *
 * IDEMPOTENCY QUA REDIS (usecase Redis chính của service này):
 * RabbitMQ theo mô hình at-least-once delivery - 1 message có thể được consumer nhận lại
 * (redeliver) nếu service crash sau khi xử lý xong nhưng trước khi kịp basicAck. Nếu không
 * chống trùng, cùng 1 orderId có thể bị CHARGE 2 LẦN.
 *
 * Bản demo trước đây dùng `Set<String>` in-memory để chống trùng - CHỈ đúng khi Payment
 * Service chạy ĐÚNG 1 instance. Thực tế production luôn chạy NHIỀU instance (để chịu tải,
 * để zero-downtime deploy), mỗi instance có vùng nhớ RIÊNG -> Set in-memory của instance A
 * không biết instance B đã charge order này chưa, "lỗ hổng" double-charge vẫn còn nguyên khi
 * scale ngang. Đây chính xác là lý do cần 1 nơi lưu trạng thái DÙNG CHUNG giữa các instance -
 * Redis đóng vai trò đó ở đây (distributed idempotency lock), không phải "cache cho nhanh".
 *
 * redisTemplate.opsForValue().setIfAbsent(key, value, ttl): tương đương lệnh Redis
 * `SET key value NX EX <ttl>` - chỉ ghi được key nếu nó CHƯA TỒN TẠI, và thao tác này là
 * ATOMIC trên toàn cụm Redis (mọi instance gọi cùng lúc, chỉ đúng 1 instance nhận được
 * kết quả true). Nhờ vậy dùng được làm "khoá phân tán": ai set key thành công trước thì
 * người đó (và chỉ người đó) được thực hiện charge.
 *
 * GIỚI HẠN CẦN BIẾT (để không ngộ nhận Redis giải quyết hết mọi thứ): `balances` bên dưới
 * vẫn là Map in-memory riêng của TỪNG instance - Redis chỉ đảm bảo "không ai charge trùng
 * đơn hàng", KHÔNG đảm bảo số dư `balances` được đồng bộ đúng giữa nhiều instance. Ở hệ
 * thống thật, bản thân số dư user phải nằm trong 1 DB dùng chung (hoặc chính Redis, dùng
 * INCR/DECR atomic) thì mới an toàn tuyệt đối khi chạy nhiều instance - phần này bản demo
 * cố tình đơn giản hoá để tập trung vào pattern idempotency.
 */
@Service
public class InMemoryPaymentGateway implements PaymentGateway {

    private static final String CHARGED_KEY_PREFIX = "payment:charged:";
    private static final String REFUNDED_KEY_PREFIX = "payment:refunded:";

    // Giả lập số dư ví ban đầu của vài user để test happy path / failure path
    private final Map<String, Long> balances = new ConcurrentHashMap<>(Map.of(
            "U001", 500_000L,   // đủ tiền
            "U002", 100_000L    // không đủ tiền cho order 250_000
    ));

    private final StringRedisTemplate redisTemplate;
    private final Duration idempotencyTtl;

    public InMemoryPaymentGateway(StringRedisTemplate redisTemplate,
                                   @Value("${payment.idempotency-ttl:PT24H}") Duration idempotencyTtl) {
        this.redisTemplate = redisTemplate;
        this.idempotencyTtl = idempotencyTtl;
    }

    @Override
    public synchronized ChargeResult charge(String orderId, String userId, long amount) {
        Boolean firstTime = redisTemplate.opsForValue()
                .setIfAbsent(CHARGED_KEY_PREFIX + orderId, "1", idempotencyTtl);

        if (Boolean.FALSE.equals(firstTime)) {
            return ChargeResult.ALREADY_CHARGED; // key đã tồn tại -> order này charge rồi
        }

        Long balance = balances.getOrDefault(userId, 0L);
        if (balance < amount) {
            // Đã "giữ chỗ" key ở trên (SETNX) nhưng charge thực tế lại fail vì hết tiền -
            // phải xoá key lại, nếu không lần thử charge SAU (vd user vừa nạp thêm tiền,
            // hoặc chỉ đơn giản là message được redeliver) sẽ bị coi nhầm là "đã charge".
            redisTemplate.delete(CHARGED_KEY_PREFIX + orderId);
            return ChargeResult.INSUFFICIENT_BALANCE;
        }

        balances.put(userId, balance - amount);
        return ChargeResult.CHARGED;
    }

    @Override
    public synchronized RefundResult refund(String orderId, String userId, long amount) {
        Boolean firstTime = redisTemplate.opsForValue()
                .setIfAbsent(REFUNDED_KEY_PREFIX + orderId, "1", idempotencyTtl);

        if (Boolean.FALSE.equals(firstTime)) {
            return RefundResult.ALREADY_REFUNDED;
        }

        balances.merge(userId, amount, Long::sum);
        return RefundResult.REFUNDED;
    }

    @Override
    public long getBalance(String userId) {
        return balances.getOrDefault(userId, 0L);
    }
}
