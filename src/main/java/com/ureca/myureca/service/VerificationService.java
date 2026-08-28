package com.ureca.myureca.service;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.dto.response.VerificationReportResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.VerificationDispatchException;
import com.ureca.myureca.exception.VerificationNotAllowedException;
import com.ureca.myureca.exception.VerificationReportCsvNotAvailableException;
import com.ureca.myureca.exception.VerificationReportFileMissingException;
import com.ureca.myureca.exception.VerificationReportNotFoundException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.repository.VerificationReportRepository;
import com.ureca.myureca.support.RedisKeys;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정합성 검증 배치 오케스트레이터. 접근 조건을 확인하고 대상 정책마다 PENDING 리포트를
 * 저장한 뒤 실제 비교 작업은 VerificationAsyncTrigger에 맡긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 이 시간을 넘겨 PENDING에 머문 리포트는 "실행 중"이 아니라 좀비로 간주한다({@link #isStale}).
     * 정합성 검증 배치는 300만 건 규모에서도 분 단위로 끝나는 작업이라 10분이면 정상 실행분을
     * 오판할 여지가 없고, 반대로 봉쇄가 10분 넘게 지속되지도 않는 절충점이다.
     */
    private static final Duration STALE_PENDING_THRESHOLD = Duration.ofMinutes(10);

    private final CouponPolicyRepository couponPolicyRepository;
    private final VerificationReportRepository verificationReportRepository;
    private final StringRedisTemplate redisTemplate;
    private final VerificationAsyncTrigger asyncTrigger;
    private final VerificationAsyncTrigger.MismatchReportWriter mismatchReportWriter;

    /** 검증 리포트 상세/다운로드(CSV) 응답에서 파일 하나를 넘기기 위한 묶음. */
    public record ReportCsvFile(Resource resource, String filename) {
    }

    /** 목록 조회. policyId/status 둘 다 선택 필터이며, 최신 실행분이 먼저 오도록 정렬은 고정한다. */
    @Transactional(readOnly = true)
    public PageResponse<VerificationReportResponse> getVerificationReports(
            Long policyId, VerificationStatus status, Pageable pageable
    ) {
        Page<VerificationReport> page;
        if (policyId != null && status != null) {
            page = verificationReportRepository.findByCouponPolicy_IdAndStatusOrderByRunAtDesc(
                    policyId, status, pageable);
        } else if (policyId != null) {
            page = verificationReportRepository.findByCouponPolicy_IdOrderByRunAtDesc(policyId, pageable);
        } else if (status != null) {
            page = verificationReportRepository.findByStatusOrderByRunAtDesc(status, pageable);
        } else {
            page = verificationReportRepository.findAllByOrderByRunAtDesc(pageable);
        }
        return PageResponse.from(page.map(VerificationReportResponse::from));
    }

    /** 단건 상세 조회. PENDING 리포트를 폴링하는 용도로도 쓰인다. */
    @Transactional(readOnly = true)
    public VerificationReportResponse getVerificationReport(Long id) {
        return VerificationReportResponse.from(findReportOrThrow(id));
    }

    /**
     * CSV 다운로드용 파일 조회
     */
    @Transactional(readOnly = true)
    public ReportCsvFile getVerificationReportCsv(Long id) {
        VerificationReport report = findReportOrThrow(id);
        String reportUrl = report.getReportUrl();
        if (reportUrl == null) {
            throw new VerificationReportCsvNotAvailableException(id, report.getStatus());
        }

        Path resolved;
        try {
            resolved = mismatchReportWriter.resolveExistingFile(reportUrl);
        } catch (IllegalStateException e) {
            throw new VerificationReportFileMissingException(id, reportUrl);
        }

        Resource resource = new FileSystemResource(resolved);
        if (!resource.exists() || !resource.isReadable()) {
            throw new VerificationReportFileMissingException(id, reportUrl);
        }

        String filename = "verification-report-%d-%d.csv".formatted(report.getCouponPolicy().getId(), report.getId());
        return new ReportCsvFile(resource, filename);
    }

    private VerificationReport findReportOrThrow(Long id) {
        return verificationReportRepository.findById(id)
                .orElseThrow(() -> new VerificationReportNotFoundException(id));
    }

    /** 기존 호출부(테스트 등) 호환용 — force 없이 호출하면 접근 조건을 그대로 강제한다. */
    public List<VerificationReportResponse> runVerification(Long policyId) {
        return runVerification(policyId, false);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<VerificationReportResponse> runVerification(Long policyId, boolean force) {
        List<CouponPolicy> allPolicies = couponPolicyRepository.findByDeletedAtIsNull();
        if (!force) {
            validateNoPolicyHasRemainingStock(allPolicies);
        }

        List<CouponPolicy> targets = (policyId != null)
                ? List.of(findAmong(allPolicies, policyId))
                : allPolicies;

        // 스트림 종단 연산(.toList())으로 한 번에 처리하면, 중간에 하나가 실패했을 때
        // 이미 접수된 정책이 몇 개인지 호출자가 알 방법이 없다 — for 루프로 풀어서
        // 실패 시점까지의 성공분을 예외에 담아 전달한다.
        List<VerificationReportResponse> dispatched = new ArrayList<>();
        for (CouponPolicy policy : targets) {
            try {
                dispatched.add(dispatch(policy));
            } catch (RuntimeException e) {
                List<Long> dispatchedIds = dispatched.stream().map(VerificationReportResponse::policyId).toList();
                throw new VerificationDispatchException(
                        "정책 id=" + policy.getId() + " 검증 접수 중 실패했습니다. "
                                + "이미 접수된 정책(재실행 불필요): " + dispatchedIds,
                        dispatchedIds,
                        e
                );
            }
        }
        return dispatched;
    }

    private VerificationReportResponse dispatch(CouponPolicy policy) {
        // 중복 디스패치 방지
        Optional<VerificationReport> existingPending = verificationReportRepository
                .findFirstByCouponPolicy_IdAndStatus(policy.getId(), VerificationStatus.PENDING);
        if (existingPending.isPresent()) {
            VerificationReport inFlight = existingPending.get();
            if (!isStale(inFlight)) {
                return VerificationReportResponse.from(inFlight);
            }
            expireStalePending(policy, inFlight);
        }

        VerificationReport pending = verificationReportRepository.save(
                VerificationReport.pending(policy, LocalDateTime.now(ZONE))
        );
        asyncTrigger.execute(pending.getId());
        return VerificationReportResponse.from(pending);
    }

    /**
     * PENDING 리포트가 정상 실행 중인 것인지, 영영 끝나지 않을 좀비인지 판단한다.
     *
     * <p>이 판단이 필요한 이유: PENDING 리포트가 하나라도 있으면 위 {@link #dispatch}는 새 검증을
     * 접수하지 않는다. 즉 <b>PENDING에 갇힌 리포트 하나가 그 정책의 검증을 영구히 봉쇄한다.</b>
     * 비동기 실행이 FAILED를 남기지 못하고 죽는 경로는 {@code VerificationAsyncTrigger}의
     * 트랜잭션 분리로 막았지만, 그걸로도 못 막는 경우가 남는다 — 검증 도중 애플리케이션 자체가
     * 죽으면(배포·OOM·컨테이너 재시작) FAILED를 남길 주체가 아예 사라진다. 그런 리포트는 재기동
     * 후에도 영원히 PENDING이므로, 시간 기준 탈출구가 없으면 그 정책은 수동 DB 조작 없이는
     * 다시는 검증할 수 없다.
     */
    private boolean isStale(VerificationReport pending) {
        return pending.getRunAt() != null
                && pending.getRunAt().isBefore(LocalDateTime.now(ZONE).minus(STALE_PENDING_THRESHOLD));
    }

    /** 좀비 PENDING을 FAILED로 확정해 길을 터준다. {@code runVerification}이 NOT_SUPPORTED라
     *  활성 트랜잭션이 없으므로 더티체킹에 기대지 않고 명시적으로 저장한다. */
    private void expireStalePending(CouponPolicy policy, VerificationReport stale) {
        try {
            stale.fail("이전 실행이 " + STALE_PENDING_THRESHOLD.toMinutes()
                    + "분 넘게 PENDING에 머물러 좀비로 판단하고 종료했습니다 (실행 중 프로세스 종료 추정).");
            verificationReportRepository.save(stale);
            log.warn("정책 id={}의 PENDING 리포트(id={}, runAt={})가 {}분 넘게 방치돼 FAILED로 정리하고 "
                            + "새 검증을 접수합니다.",
                    policy.getId(), stale.getId(), stale.getRunAt(), STALE_PENDING_THRESHOLD.toMinutes());
        } catch (Exception e) {
            // 정리에 실패해도 새 검증 접수 자체는 막지 않는다 — 봉쇄를 푸는 게 목적이기 때문이다.
            log.warn("정책 id={}의 좀비 PENDING 리포트(id={}) 정리에 실패했습니다.",
                    policy.getId(), stale.getId(), e);
        }
    }

    /**
     * 접근 조건: 재고가 남아있는 정책이 하나라도 있으면 실행을 막는 게 아니라 확인을 요구한다.
     * force=true로 재호출하면 이 확인을 건너뛰고 그대로 진행한다(성능·정합성 리스크는 호출자 책임).
     */
    private void validateNoPolicyHasRemainingStock(List<CouponPolicy> policies) {
        for (CouponPolicy policy : policies) {
            if (currentStock(policy.getId()) > 0) {
                throw new VerificationNotAllowedException(
                        "아직 쿠폰 발급이 진행중인 이벤트가 있습니다. "
                                + "검증 실행 시 성능 및 정합성 문제가 발생할 수 있습니다. 그래도 진행하시겠습니까?");
            }
        }
    }

    /** Redis 실시간 재고 카운터. 키가 없으면 Lua Fast-Fail과 동일하게 "재고 없음"으로 취급한다. */
    private long currentStock(Long policyId) {
        String value = redisTemplate.opsForValue().get(RedisKeys.couponStock(policyId));
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private CouponPolicy findAmong(List<CouponPolicy> policies, Long policyId) {
        return policies.stream()
                .filter(p -> p.getId().equals(policyId))
                .findFirst()
                .orElseThrow(() -> new CouponPolicyNotFoundException(policyId));
    }
}
