package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponPolicyRepository extends JpaRepository<CouponPolicy, Long> {

    Page<CouponPolicy> findByDeletedAtIsNull(Pageable pageable);

    Optional<CouponPolicy> findByIdAndDeletedAtIsNull(Long id);
}
