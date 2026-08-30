package com.ureca.myureca.service;

import com.ureca.myureca.repository.CouponIssueRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대용량 쿠폰 만료 시, 각 청크(Chunk)를 독립된 트랜잭션으로 커밋하여 DB Lock 점유 시간을 최소화하는 실행 컴포넌트.
 */
@Component
@RequiredArgsConstructor
public class CouponExpirationChunkExecutor {

    private final CouponIssueRepository couponIssueRepository;

    /**
     * 지정된 정책의 ISSUED 쿠폰 중 최대 chunkSize 만큼만 EXPIRED 로 변경하고 즉시 커밋한다.
     *
     * @param policyId  쿠폰 정책 ID
     * @param now       만료 처리 기준 시각
     * @param chunkSize 한 번에 처리할 청크 크기 (기본: 5000)
     * @return 이번 청크에서 실제로 변경된 행(row) 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireChunk(Long policyId, LocalDateTime now, int chunkSize) {
        return couponIssueRepository.bulkExpireChunkByPolicyId(policyId, now, chunkSize);
    }
}
