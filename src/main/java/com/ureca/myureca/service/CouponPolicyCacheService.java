package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 대규모 대기열 트래픽 유입 시 DB 커넥션 풀(HikariCP) 고갈을 방어하기 위한 정책 메타 In-Memory 캐시 서비스.
 *
 * <p>대기열 등록 요청이 초당 수천~수만 건 몰려도 DB IOPS를 0에 가깝게 유지하며,
 * 정책이 수정/삭제될 때만 캐시를 즉시 무효화(Evict)한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponPolicyCacheService {

    private final CouponPolicyRepository couponPolicyRepository;
    private final Map<Long, CachedPolicy> cache = new ConcurrentHashMap<>();

    /** 로컬 캐시 유효 시간: 5초 (짧은 TTL로 DB 변경사항의 최종 일관성 보장) */
    private static final long CACHE_TTL_MS = 5000L;

    public record CachedPolicy(
            Long id,
            LocalDateTime openAt,
            LocalDateTime closeAt,
            int totalQuantity,
            long cachedAt
    ) {
        public CachedPolicy(Long id, LocalDateTime openAt, LocalDateTime closeAt, long cachedAt) {
            this(id, openAt, closeAt, 10000, cachedAt);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MS;
        }
    }

    /**
     * 정책 메타정보 조회 (Cache-Aside 패턴).
     * 캐시 Miss 또는 만료 시에만 DB를 조회한다.
     */
    public CachedPolicy getPolicy(Long policyId) {
        CachedPolicy cached = cache.get(policyId);
        if (cached != null && !cached.isExpired()) {
            return cached;
        }

        CouponPolicy policy = couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId)
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));

        CachedPolicy newCache = new CachedPolicy(
                policy.getId(),
                policy.getOpenAt(),
                policy.getCloseAt(),
                policy.getTotalQuantity(),
                System.currentTimeMillis()
        );
        cache.put(policyId, newCache);
        return newCache;
    }

    private volatile CachedPolicyList cachedActivePolicies;

    public record CachedPolicyList(java.util.List<CachedPolicy> policies, long cachedAt) {
        public boolean isExpired() {
            return System.currentTimeMillis() - cachedAt > CACHE_TTL_MS;
        }
    }

    /**
     * 스케줄러를 위한 활성 정책 목록 캐시 조회.
     * 매초 DB를 찌르지 않고 메모리에서 캐싱(5초 TTL)하여 DB IOPS를 0으로 유지한다.
     */
    public java.util.List<CachedPolicy> getActivePolicies() {
        CachedPolicyList current = cachedActivePolicies;
        if (current != null && !current.isExpired()) {
            return current.policies();
        }

        java.util.List<CouponPolicy> list = couponPolicyRepository.findByDeletedAtIsNull(org.springframework.data.domain.Pageable.unpaged()).getContent();
        java.util.List<CachedPolicy> cachedList = list.stream()
                .map(p -> new CachedPolicy(p.getId(), p.getOpenAt(), p.getCloseAt(), p.getTotalQuantity(), System.currentTimeMillis()))
                .toList();

        cachedActivePolicies = new CachedPolicyList(cachedList, System.currentTimeMillis());
        return cachedList;
    }

    /** 정책 수정/삭제/생성 시 캐시 즉시 제거 */
    public void evict(Long policyId) {
        cache.remove(policyId);
        cachedActivePolicies = null;
        log.debug("CouponPolicy cache evicted. policyId={}", policyId);
    }
}
