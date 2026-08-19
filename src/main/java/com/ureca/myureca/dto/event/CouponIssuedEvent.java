package com.ureca.myureca.dto.event;

import java.time.LocalDateTime;

public record CouponIssuedEvent(
                Long policyId,
                Long userId,
                String receiptId,
                LocalDateTime issuedAt) {
}
