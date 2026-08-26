package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.CouponHistory;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponHistoryRepository extends JpaRepository<CouponHistory, Long> {

    @EntityGraph(attributePaths = {"couponIssue", "couponIssue.user"})
    Optional<CouponHistory> findByRequestId(String requestId);
}
