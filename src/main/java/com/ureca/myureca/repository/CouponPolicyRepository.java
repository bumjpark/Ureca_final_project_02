package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponPolicyRepository extends JpaRepository<CouponPolicy, Long> {

    Page<CouponPolicy> findByDeletedAtIsNull(Pageable pageable);

    Optional<CouponPolicy> findByIdAndDeletedAtIsNull(Long id);

    /** 검증 배치가 전체 정책을 순회할 때 사용 (페이징 없이 전량 조회). */
    List<CouponPolicy> findByDeletedAtIsNull();
}
