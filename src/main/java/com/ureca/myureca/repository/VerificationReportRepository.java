package com.ureca.myureca.repository;

import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationReportRepository extends JpaRepository<VerificationReport, Long> {

    /** 중복 디스패치 방지: 이 정책에 이미 PENDING(처리 중) 리포트가 있는지 확인한다. */
    Optional<VerificationReport> findFirstByCouponPolicy_IdAndStatus(Long policyId, VerificationStatus status);

    // 목록 조회: policyId/status 필터는 각각 선택이라 조합 4개를 그대로 둔다(MyCouponController와 동일 패턴).
    // idx_verification_policy(coupon_policy_id, run_at) 인덱스가 있어 정책 지정 조회는 그대로 인덱스를 탄다.
    Page<VerificationReport> findAllByOrderByRunAtDesc(Pageable pageable);

    Page<VerificationReport> findByCouponPolicy_IdOrderByRunAtDesc(Long policyId, Pageable pageable);

    Page<VerificationReport> findByStatusOrderByRunAtDesc(VerificationStatus status, Pageable pageable);

    Page<VerificationReport> findByCouponPolicy_IdAndStatusOrderByRunAtDesc(
            Long policyId, VerificationStatus status, Pageable pageable);
}
