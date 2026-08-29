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
import org.hibernate.annotations.DynamicUpdate;

/**
 * mock_notification_bulk_job 테이블 매핑 엔티티.
 * 정책별 일괄 발송 1건의 진행 상태 — 대상자 수 대비 지금까지 끝난 성공/실패 건수를 들고 있다.
 * 발송 루프는 {@code MockNotificationService.sendBulkByPolicy}가 단일 스레드로 순차 실행하므로
 * 이 엔티티에 대한 동시 갱신 경합은 없다.
 */
@Entity
@DynamicUpdate
@Table(
        name = "mock_notification_bulk_job",
        indexes = @Index(name = "idx_mock_bulk_job_policy", columnList = "coupon_policy_id, created_at")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MockNotificationBulkJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "coupon_policy_id", nullable = false)
    private Long couponPolicyId;

    @Column(name = "template_id", nullable = false, length = 100)
    private String templateId;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "target_count", nullable = false)
    private Integer targetCount;

    @Column(name = "sent_count", nullable = false)
    private Integer sentCount = 0;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount = 0;

    @Column(
            name = "status",
            nullable = false,
            length = 20,
            check = @CheckConstraint(name = "chk_mock_bulk_job_status", constraint = "status in ('IN_PROGRESS','COMPLETED')")
    )
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public MockNotificationBulkJob(Long couponPolicyId, String templateId, String message, int targetCount) {
        this.couponPolicyId = couponPolicyId;
        this.templateId = templateId;
        this.message = message;
        this.targetCount = targetCount;
        this.sentCount = 0;
        this.failedCount = 0;
        this.status = "IN_PROGRESS";
    }

    public void recordSent() {
        this.sentCount++;
    }

    public void recordFailed() {
        this.failedCount++;
    }

    /** 완료 처리 — 대상자 수만큼 실제로 처리를 시도한 뒤(성공+실패 합이 targetCount) 호출된다. */
    public void complete() {
        this.status = "COMPLETED";
        this.completedAt = LocalDateTime.now();
    }

    public int completedCount() {
        return sentCount + failedCount;
    }
}
