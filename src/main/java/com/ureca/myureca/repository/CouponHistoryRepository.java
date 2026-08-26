package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.CouponHistory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponHistoryRepository extends JpaRepository<CouponHistory, Long> {

    List<CouponHistory> findByCouponIssueIdOrderByCreatedAtAsc(Long couponIssueId);
}
