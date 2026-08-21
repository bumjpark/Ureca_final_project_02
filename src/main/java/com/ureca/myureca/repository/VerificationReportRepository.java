package com.ureca.myureca.repository;

import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerificationReportRepository extends JpaRepository<VerificationReport, Long> {

    /** 중복 디스패치 방지: 이 정책에 이미 PENDING(처리 중) 리포트가 있는지 확인한다. */
    Optional<VerificationReport> findFirstByCouponPolicy_IdAndStatus(Long policyId, VerificationStatus status);
}
