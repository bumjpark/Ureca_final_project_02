package com.ureca.myureca.repository.coupon;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CouponPolicyRepository extends JpaRepository<CouponPolicy, Long> {
    
}