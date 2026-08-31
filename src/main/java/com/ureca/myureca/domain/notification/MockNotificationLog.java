package com.ureca.myureca.domain.notification;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

/**
 * mock_notification_log 테이블 매핑 엔티티.
 * 실제 외부 연동을 흉내 낸 Mock 카카오 알림톡(FR-5) 발송 1건의 결과를 기록한다 —
 * 단건 발송, 정책별 일괄 발송 둘 다 이 테이블에 쌓인다(단건이면 couponPolicyId는 null).
 */
@Entity
@Table(
        name = "mock_notification_log",
        indexes = {
                @Index(name = "idx_mock_notification_policy", columnList = "coupon_policy_id, created_at"),
                @Index(name = "idx_mock_notification_user", columnList = "user_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MockNotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 일괄 발송의 대상 정책. 단건 발송이면 null. */
    @Column(name = "coupon_policy_id")
    private Long couponPolicyId;

    @Column(name = "template_id", nullable = false, length = 100)
    private String templateId;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(
            name = "status",
            nullable = false,
            length = 20,
            check = @CheckConstraint(name = "chk_mock_notification_status", constraint = "status in ('SENT','FAILED')")
    )
    private String status;

    @Column(name = "message_id", nullable = false, length = 100)
    private String messageId;

    @Column(name = "fail_reason", length = 255)
    private String failReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private MockNotificationLog(
            Long userId, Long couponPolicyId, String templateId, String message,
            String status, String messageId, String failReason
    ) {
        this.userId = userId;
        this.couponPolicyId = couponPolicyId;
        this.templateId = templateId;
        this.message = message;
        this.status = status;
        this.messageId = messageId;
        this.failReason = failReason;
    }

    public static MockNotificationLog sent(
            Long userId, Long couponPolicyId, String templateId, String message, String messageId
    ) {
        return new MockNotificationLog(userId, couponPolicyId, templateId, message, "SENT", messageId, null);
    }

    public static MockNotificationLog failed(
            Long userId, Long couponPolicyId, String templateId, String message, String messageId, String failReason
    ) {
        return new MockNotificationLog(userId, couponPolicyId, templateId, message, "FAILED", messageId, failReason);
    }
}
