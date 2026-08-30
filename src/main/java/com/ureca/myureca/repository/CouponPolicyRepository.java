package com.ureca.myureca.repository;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponPolicyRepository extends JpaRepository<CouponPolicy, Long> {

    Page<CouponPolicy> findByDeletedAtIsNull(Pageable pageable);

    Optional<CouponPolicy> findByIdAndDeletedAtIsNull(Long id);

    /** 검증 배치가 전체 정책을 순회할 때 사용 (페이징 없이 전량 조회). */
    List<CouponPolicy> findByDeletedAtIsNull();

    /** 마감 일시(closeAt)가 현재 시각 이전인 삭제되지 않은 만료 정책 조회 */
    @Query("SELECT cp FROM CouponPolicy cp WHERE cp.closeAt IS NOT NULL AND cp.closeAt <= :now AND cp.deletedAt IS NULL")
    List<CouponPolicy> findExpiredPolicies(@Param("now") LocalDateTime now);

    /** ScaleTestService(300만 건 규모 데모)가 자기가 만든 정책만 찾을 때 — 소프트 삭제 여부 무관. */
    List<CouponPolicy> findByTitleStartingWithOrderByIdAsc(String titlePrefix);
}
