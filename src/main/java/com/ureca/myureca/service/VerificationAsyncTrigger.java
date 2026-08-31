package com.ureca.myureca.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.domain.reconciliation.ReconciliationLog;
import com.ureca.myureca.domain.reconciliation.ReconciliationType;
import com.ureca.myureca.domain.verification.VerificationReport;
import com.ureca.myureca.domain.verification.VerificationStatus;
import com.ureca.myureca.dto.event.RedisOnlyDriftDetail;
import com.ureca.myureca.repository.CouponHistoryRepository;
import com.ureca.myureca.repository.CouponHistoryStatusSnapshot;
import com.ureca.myureca.repository.CouponIssueLifecycleSnapshot;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.QueueJoinLogRepository;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import com.ureca.myureca.repository.VerificationReportRepository;
import com.ureca.myureca.support.RedisKeys;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * PENDING으로 접수된 검증 리포트를 백그라운드에서 처리
 */
@Slf4j
@Component
public class VerificationAsyncTrigger {

    private final CouponIssueRepository couponIssueRepository;
    private final VerificationReportRepository verificationReportRepository;
    private final StringRedisTemplate redisTemplate;
    private final MismatchReportWriter mismatchReportWriter;
    private final CouponHistoryRepository couponHistoryRepository;
    private final QueueJoinLogRepository queueJoinLogRepository;
    private final ReconciliationLogRepository reconciliationLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * 이 시간을 넘도록 {@code reserved}에 남아있으면 미아 예약으로 본다({@link #readStaleReservedUserIds}).
     * 정상 경로는 1초 안에 끝나므로 넉넉히 잡아도 오탐이 없다 — 다만 <b>부하 테스트 중에 검증을
     * 돌리면</b> 아직 처리 중인 정상 예약이 임계를 넘을 수 있으므로, 그럴 때는 값을 더 키우거나
     * 부하가 끝난 뒤 검증한다.
     */
    private final java.time.Duration staleReservedThreshold;

    public VerificationAsyncTrigger(
            CouponIssueRepository couponIssueRepository,
            VerificationReportRepository verificationReportRepository,
            StringRedisTemplate redisTemplate,
            MismatchReportWriter mismatchReportWriter,
            CouponHistoryRepository couponHistoryRepository,
            QueueJoinLogRepository queueJoinLogRepository,
            ReconciliationLogRepository reconciliationLogRepository,
            ObjectMapper objectMapper,
            ObjectProvider<VerificationAsyncTrigger> selfProvider,
            @Value("${app.verification.stale-reserved-threshold:PT5M}") java.time.Duration staleReservedThreshold
    ) {
        this.couponIssueRepository = couponIssueRepository;
        this.verificationReportRepository = verificationReportRepository;
        this.redisTemplate = redisTemplate;
        this.mismatchReportWriter = mismatchReportWriter;
        this.couponHistoryRepository = couponHistoryRepository;
        this.queueJoinLogRepository = queueJoinLogRepository;
        this.reconciliationLogRepository = reconciliationLogRepository;
        this.objectMapper = objectMapper;
        this.selfProvider = selfProvider;
        this.staleReservedThreshold = staleReservedThreshold;
    }

    /**
     * 자기 자신의 Spring 프록시. {@link #execute}는 트랜잭션 <b>밖</b>에 있어야 하고
     * ({@link #execute} 주석 참고) {@link #performVerification}·{@link #markFailed}는 각각 별개
     * 트랜잭션이어야 하는데, 같은 클래스 안에서 그냥 호출하면 self-invoke라 AOP 프록시를 안 거쳐
     * {@code @Transactional}이 통째로 무시된다. {@code ObjectProvider}로 지연 조회하면 자기 참조
     * 순환 의존성 없이 프록시를 얻을 수 있다.
     */
    private final ObjectProvider<VerificationAsyncTrigger> selfProvider;

    /**
     * 검증 실행 오케스트레이션 전담. <b>이 메서드에는 {@code @Transactional}을 붙이면 안 된다.</b>
     *
     * <p>예전에는 여기에 {@code @Transactional}이 붙어 있고 {@code catch} 안에서 리포트를 FAILED로
     * 바꿨는데, 실패 원인이 DB 예외인 경우 Hibernate가 이미 그 트랜잭션을 rollback-only로 마킹한
     * 뒤라 {@code report.fail()}의 더티체킹이 절대 커밋되지 않았다(이슈 #11/#17에서 Kafka 컨슈머에
     * 대해 잡았던 것과 정확히 같은 패턴). 그 결과가 단순 로그 누락이 아니라는 게 문제였다 —
     * {@code VerificationService.dispatch()}는 PENDING 리포트가 있으면 새 검증을 접수하지 않으므로,
     * 한 번 PENDING에 갇히면 <b>그 정책은 다시는 검증을 돌릴 수 없었다.</b> 게다가 이 메서드는
     * {@code @Async}라 예외가 아무 데도 안 올라가서 조용히 그렇게 됐다.
     *
     * <p>그래서 트랜잭션 경계를 둘로 쪼갰다: 실제 대사 작업({@link #performVerification})이 자기
     * 트랜잭션에서 롤백되더라도, 실패 기록({@link #markFailed})은 그와 무관한 새 트랜잭션에서
     * 독립적으로 커밋된다.
     */
    @Async("verificationTaskExecutor")
    public void execute(Long reportId) {
        VerificationAsyncTrigger self = selfProvider.getObject();
        try {
            self.performVerification(reportId);
        } catch (Exception e) {
            // 백그라운드 스레드라 예외를 던져봐야 받아줄 호출자가 없다 — 여기서 잡아 로그로 남긴다.
            log.error("검증 배치 비동기 실행 실패. reportId={}", reportId, e);
            self.markFailed(reportId, e);
        }
    }

    /**
     * 실패한 리포트를 FAILED로 확정한다.
     *
     * <p>{@code REQUIRES_NEW}인 이유: 호출 시점에 이미 {@link #performVerification}의 트랜잭션이
     * 롤백된 뒤이고, 혹시라도 바깥에 rollback-only로 마킹된 트랜잭션이 살아있다면 그 안에서
     * 쓰기를 시도해봐야 커밋되지 않는다. 실패 기록은 검증 작업의 성패와 완전히 독립적으로
     * 남아야 하므로 항상 새 트랜잭션에서 수행한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long reportId, Exception cause) {
        try {
            verificationReportRepository.findById(reportId).ifPresent(report -> {
                if (report.getStatus() == VerificationStatus.PENDING) {
                    report.fail(summarize(cause));
                }
            });
        } catch (Exception markFailure) {
            log.error("검증 실패 상태 기록 자체가 실패함. reportId={}", reportId, markFailure);
        }
    }

    private String summarize(Exception e) {
        String message = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /**
     * 실제 대사(비교) 작업. {@code public}인 이유는 가시성이 필요해서가 아니라, Spring의
     * {@code AnnotationTransactionAttributeSource}가 <b>public 메서드에만</b>
     * {@code @Transactional}을 적용하기 때문이다 — package-private으로 두면 프록시를 거쳐도
     * 트랜잭션이 조용히 무시되고, 이 메서드가 의존하는 지연 로딩·더티체킹이 전부 깨진다.
     */
    @Transactional
    public void performVerification(Long reportId) {
        VerificationReport report = verificationReportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalStateException("검증 리포트를 찾을 수 없습니다. id=" + reportId));

        CouponPolicy policy = report.getCouponPolicy();
        Long policyId = policy.getId();
        LocalDateTime runAt = report.getRunAt();

        Set<Long> dbUserIds = new HashSet<>(couponIssueRepository.findUserIdsByCouponPolicyId(policyId));
        Set<Long> redisUserIds = readRedisIssuedUserIds(policyId);
        long totalReserved = readRedisReservedCount(policyId);

        int overIssuedCount = Math.max(0, dbUserIds.size() - policy.getTotalQuantity());
        if (overIssuedCount > 0) {
            log.error("🚨 정책 id={} 초과발급 의심: 발급 {}건 > 재고 {}건 (NFR-1 위반)",
                    policyId, dbUserIds.size(), policy.getTotalQuantity());
        }

        Set<Long> redisOnlyUserIds = new HashSet<>(redisUserIds);
        redisOnlyUserIds.removeAll(dbUserIds);
        Set<Long> dbOnlyUserIds = new HashSet<>(dbUserIds);
        dbOnlyUserIds.removeAll(redisUserIds);
        int diffMismatchCount = redisOnlyUserIds.size() + dbOnlyUserIds.size();

        if (!redisOnlyUserIds.isEmpty()) {
            registerRedisOnlyDrift(policyId, redisOnlyUserIds, runAt, "REDIS_ONLY");
        }

        // Check D: 미아 예약(stale RESERVED) — Lua가 재고를 깎고 reserved에 넣었는데 오래도록
        // 아무도 확정해주지 않은 유저. 정상 발급이라면 컨슈머가 1초 안에 issued로 옮기므로,
        // 임계 시간을 넘긴 항목은 그 사이에 이벤트가 증발했다는 뜻이다. 아래 카운트/CSV에 계속
        // 쓰이므로 목록 자체는 여기서 직접 계산해 갖고 있는다 — 등록(reconciliation_log 적재)만
        // RedisAutoRecoveryScheduler가 주기적으로 부르는 것과 같은 로직({@link
        // #detectAndRegisterStaleReserved})을 재사용한다. eventKey로 중복 방지되므로 두 경로가
        // 겹쳐 불러도 안전하다.
        Set<Long> staleReservedUserIds = readStaleReservedUserIds(policyId);
        staleReservedUserIds.removeAll(dbUserIds); // 이미 DB에 있으면 ZREM만 못 한 것이라 유실이 아니다
        if (!staleReservedUserIds.isEmpty()) {
            log.error("🚨 정책 id={} 미아 예약(stale RESERVED) {}건 — 재고만 깎이고 발급이 확정되지 않은 유저",
                    policyId, staleReservedUserIds.size());
            registerRedisOnlyDrift(policyId, staleReservedUserIds, runAt, "RESERVED_STALE");
        }

        Integer currentRedisStock = readRedisStockCounter(policyId);
        int stockLeakCount;
        if (currentRedisStock == null) {
            log.warn("정책 id={} 재고 카운터({})가 초기화된 적이 없어 재고 누수 체크를 건너뜁니다.",
                    policyId, RedisKeys.couponStock(policyId));
            stockLeakCount = 0;
        } else {
            stockLeakCount = computeStockLeakCount(
                    policyId, policy.getTotalQuantity(), currentRedisStock, dbUserIds.size(), totalReserved);
        }

        List<CouponIssueLifecycleSnapshot> lifecycleSnapshots = fetchLifecycleSnapshots(policyId);
        Map<Long, IssueStatus> latestHistoryStatusByIssueId = fetchLatestHistoryStatusByIssueId(policyId);
        List<LifecycleAnomaly> lifecycleAnomalies =
                detectLifecycleAnomalies(policyId, lifecycleSnapshots, latestHistoryStatusByIssueId);

        // Check C: 선착순(FCFS) 순서 검증 — "대기열 도착 순서 상위 N명"과 "실제 DB 발급자 집합"을 비교
        int liveN = Math.min(dbUserIds.size(), policy.getTotalQuantity());
        long coveredCount = queueJoinLogRepository.countByCouponPolicyIdAndQueueRankLessThanEqual(
                policyId, (long) liveN);
        Set<Long> expectedTopN;
        int fcfsMismatchCount;
        boolean fcfsChecked;
        if (coveredCount < liveN) {
            log.warn("정책 id={} queue_join_log의 순번 구간 [1,{}] 적재분({}건)이 아직 채워지지 않아 "
                    + "선착순(FCFS) 검증을 건너뜁니다 (queue-join-events 컨슈머 미가동 또는 캐치업 중으로 추정).",
                    policyId, liveN, coveredCount);
            expectedTopN = Set.of();
            fcfsMismatchCount = 0;
            fcfsChecked = false;
        } else {
            expectedTopN = fetchExpectedTopN(policyId, liveN);
            fcfsMismatchCount = countMismatch(dbUserIds, expectedTopN);
            fcfsChecked = true;
        }

        int mismatchCount = diffMismatchCount + overIssuedCount + stockLeakCount
                + lifecycleAnomalies.size() + fcfsMismatchCount + staleReservedUserIds.size();
        VerificationStatus status = (mismatchCount == 0) ? VerificationStatus.SUCCESS : VerificationStatus.MISMATCH_FOUND;

        if (totalReserved > 0) {
            // 임계 시간을 안 넘긴 RESERVED는 "지금 처리 중"이라 정상이다 — 위 Check D가 걸러낸
            // 미아 예약만 위반으로 센다. 이 로그는 그 둘을 합친 현황이다.
            log.warn("정책 id={} 검증 시점에 RESERVED가 {}건 남아있음(그중 미아 예약 {}건) — "
                            + "나머지는 마감 전 정상 처리 중일 수 있음",
                    policyId, totalReserved, staleReservedUserIds.size());
        }
        if (fcfsMismatchCount > 0) {
            log.error("🚨 정책 id={} 선착순(FCFS) 위반 의심: {}쌍 (도착순 상위 N명 ↔ 실제 발급자 불일치)",
                    policyId, fcfsMismatchCount);
        }

        Path csvPath = null;
        if (diffMismatchCount > 0 || overIssuedCount > 0 || stockLeakCount > 0
                || !lifecycleAnomalies.isEmpty() || fcfsMismatchCount > 0 || !staleReservedUserIds.isEmpty()) {
            MismatchFindings findings = new MismatchFindings(
                    dbUserIds, redisUserIds, overIssuedCount, stockLeakCount, lifecycleAnomalies, expectedTopN,
                    staleReservedUserIds, fcfsChecked);
            csvPath = mismatchReportWriter.write(policyId, runAt, findings);
        }

        report.complete(dbUserIds.size(), (int) totalReserved, mismatchCount, status);
        if (csvPath != null) {
            report.attachReportUrl(csvPath.toString());
        }
    }

    /**
     * {@code issued} SET 전체 멤버 조회. {@code SMEMBERS}(단일 O(N) 블로킹 명령)가 아니라
     * {@code SSCAN} 커서 반복을 쓴다 — 실측(2026-08-30, 300만 건 규모 정합성 검증)으로
     * {@code SMEMBERS}가 300만 멤버에서 서버 측만 1.53초를 잡아먹어, Redis 장애를 빨리
     * 감지하려고 일부러 짧게 잡은 클라이언트 타임아웃(1초, {@code spring.data.redis.timeout})을
     * 넘겨 검증 자체가 {@code QueryTimeoutException}으로 실패하는 걸 확인했다. {@code SSCAN}은
     * 한 번에 작은 배치(COUNT)만 가져오는 여러 번의 왕복으로 쪼개지므로, Redis의 단일 이벤트
     * 루프를 한 번에 오래 붙잡지 않는다.
     *
     * <p><b>COUNT 값 튜닝(실측)</b>: 처음엔 COUNT=2000으로 재검증했는데, 300만 멤버 기준 왕복이
     * 1,500번이나 필요해 검증 전체가 35초(SMEMBERS+타임아웃 5초 완화 기준) → **145초**로 4배
     * 넘게 느려졌다 — "한 번에 오래 안 붙잡는다"를 지키려고 왕복을 너무 잘게 쪼갠 대가였다.
     * SMEMBERS 실측(1.53초/300만 건 ≈ 510ns/멤버)을 기준으로 역산하면 COUNT=50,000이어도 배치
     * 하나당 서버 처리 시간은 ~25ms(1초 타임아웃 대비 40배 여유)에 불과해 안전하고, 왕복 횟수는
     * 60번으로 줄어든다 — "안전 마진은 그대로 유지하면서 왕복을 최소화"하는 절충점.
     */
    private Set<Long> readRedisIssuedUserIds(Long policyId) {
        Set<Long> result = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().count(50_000).build();
        try (Cursor<String> cursor = redisTemplate.opsForSet().scan(RedisKeys.couponIssued(policyId), options)) {
            while (cursor.hasNext()) {
                result.add(Long.valueOf(cursor.next()));
            }
        }
        return result;
    }

    private long readRedisReservedCount(Long policyId) {
        Long size = redisTemplate.opsForZSet().size(RedisKeys.couponReserved(policyId));
        return (size == null) ? 0L : size;
    }

    /**
     * Check D만 독립 실행 — {@link #performVerification}(전체 검증)과 달리 검증 리포트를
     * 만들지도, CSV를 쓰지도 않고, "재고 남은 정책이 없어야 한다"는 제약도 없다. Check D(미아
     * 예약)는 판매가 진행 중이든 아니든 안전하게 돌릴 수 있는 검사라서 그렇다 — 반면 Check
     * C(FCFS)는 판매가 끝나야 "기대 상위 N명"이 의미를 갖기 때문에 이 메서드에 넣지 않았다.
     *
     * <p>전체 검증은 사람이 눌러야만 실행되는데(재고 남은 정책이 있으면 기본적으로 거부), 그
     * 말은 곧 진짜 재고 누수(미아 예약)가 사람이 검증을 누르기 전까지 아무도 모르게 방치될 수
     * 있다는 뜻이다 — {@link RedisAutoRecoveryScheduler}가 이 메서드를 주기적으로 호출해 그
     * 공백을 메운다.
     *
     * <p>발견해도 자동으로 재발급하지 않는다 — {@link #registerRedisOnlyDrift}와 동일한 이유로
     * {@code reconciliation_log}(ISSUE_REPROCESS)에 등록해 가시성만 확보한다. 재발급 여부는
     * 여전히 사람이 판단한다.
     *
     * @return 이번 호출에서 <b>새로</b> 발견한(= 아직 {@code reconciliation_log}에 없는) 미아 예약
     *         유저 수. 이미 등록된 건은 세지 않는다 — 아래 "왜 신규만 세는가" 참고.
     */
    @Transactional
    public int detectAndRegisterStaleReserved(Long policyId) {
        Set<Long> staleReservedUserIds = readStaleReservedUserIds(policyId);
        if (staleReservedUserIds.isEmpty()) {
            return 0;
        }

        // 왜 신규만 세는가 / 왜 여기서 먼저 거르는가:
        // 미아 예약은 설계상 자동 재발급을 하지 않으므로(사람이 판단), 사람이 처리해주기 전까지
        // Redis reserved에 계속 남는다. 그래서 이 스케줄러는 같은 건을 매 틱(60초) 다시 발견한다.
        // 예전에는 그 상태로 아래 findUserIdsByCouponPolicyId(정책 전체 user_id를 통째로 로드 —
        // 정책이 클수록 비싸다)까지 매번 실행하고, 등록 단계에서야 중복을 걸러냈다. 그 결과
        // "이미 다 등록해둔 1,000건"을 위해 60초마다 영원히 같은 무거운 조회를 반복했다.
        // 이미 등록된 eventKey는 인덱스 조회 한 번으로 싸게 걸러지므로, 여기서 먼저 잘라내고
        // 새로 볼 게 없으면 곧바로 빠져나간다(2026-08-31).
        Set<Long> unregistered = filterUnregistered(policyId, staleReservedUserIds, "RESERVED_STALE");
        if (unregistered.isEmpty()) {
            return 0;
        }

        Set<Long> dbUserIds = new HashSet<>(couponIssueRepository.findUserIdsByCouponPolicyId(policyId));
        unregistered.removeAll(dbUserIds); // 이미 DB에 있으면 ZREM만 못 한 것이라 유실이 아니다
        if (unregistered.isEmpty()) {
            return 0;
        }
        log.error("🚨 정책 id={} 미아 예약(stale RESERVED) 신규 {}건 — 재고만 깎이고 발급이 확정되지 않은 유저",
                policyId, unregistered.size());
        registerRedisOnlyDrift(policyId, unregistered, LocalDateTime.now(), "RESERVED_STALE");
        return unregistered.size();
    }

    /**
     * 아직 {@code reconciliation_log}에 등록되지 않은 유저만 골라낸다(청크 단위 IN 조회).
     *
     * <p>{@link #registerRedisOnlyDrift}도 내부에서 같은 확인을 한 번 더 한다 — 중복처럼 보이지만
     * 의도한 것이다. 이 메서드는 "비싼 후속 작업을 할 가치가 있는가"를 미리 판단하는 단축용이고,
     * 등록 시점의 확인은 그 사이 다른 경로(검증 배치)가 먼저 등록했을 경쟁 상황까지 막는
     * 최종 방어선이다. 여기서 걸러진 뒤라 등록 단계의 확인 대상은 이미 작다.
     */
    private Set<Long> filterUnregistered(Long policyId, Set<Long> userIds, String discrepancyType) {
        String keyPrefix = driftEventKeyPrefix(policyId, discrepancyType);
        List<Long> ordered = new ArrayList<>(userIds);
        ordered.sort(null);

        Set<Long> unregistered = new LinkedHashSet<>();
        for (int from = 0; from < ordered.size(); from += DRIFT_REGISTER_CHUNK_SIZE) {
            List<Long> chunk = ordered.subList(
                    from, Math.min(from + DRIFT_REGISTER_CHUNK_SIZE, ordered.size()));
            Set<String> existing = reconciliationLogRepository.findExistingEventKeys(
                    chunk.stream().map(userId -> keyPrefix + userId).toList());
            for (Long userId : chunk) {
                if (!existing.contains(keyPrefix + userId)) {
                    unregistered.add(userId);
                }
            }
        }
        return unregistered;
    }

    /**
     * 유형별로 키를 나눠, 같은 유저가 두 유형에 모두 해당해도 각각 한 행씩만 남게 한다.
     * REDIS_ONLY → {@code "verify-redis-only:{policyId}:{userId}"}
     */
    private String driftEventKeyPrefix(Long policyId, String discrepancyType) {
        return "verify-" + discrepancyType.toLowerCase().replace('_', '-') + ':' + policyId + ':';
    }

    /**
     * Check D: 임계 시간을 넘도록 {@code reserved}에 남아있는 유저(미아 예약).
     *
     * <p>{@code issue_coupon.lua}는 재고를 깎으면서 {@code reserved} ZSET에 <b>score = 예약 시각(epoch
     * millis)</b>으로 넣는다. 정상 경로에서는 컨슈머가 DB 커밋 직후 {@code reserved → issued}로
     * 옮기므로 이 항목은 보통 1초 안에 사라진다. 그러므로 임계 시간을 넘긴 항목은 <b>재고는
     * 깎였는데 발급 이벤트가 중간에 증발한 건</b>이다 — Lua 성공 직후 Kafka 발행 전에 프로세스가
     * 죽는 경로({@code KafkaCouponEventProducer.recordPublishFailure}가 호출될 기회조차 없는 경우)가
     * 정확히 여기로 떨어진다.
     *
     * <p>이 검사가 없으면 그 유저는 어디에도 안 잡힌다 — {@code issued} SET에 없으니 REDIS_ONLY도
     * 아니고, {@code computeStockLeakCount}는 {@code totalReserved}를 "처리 중"으로 세기 때문에
     * 재고 누수로도 안 잡힌다. 재고만 깎인 채 영원히 발급을 못 받고, 같은 정책에 재진입도
     * 막힌다(Lua가 409로 거절).
     */
    private Set<Long> readStaleReservedUserIds(Long policyId) {
        long cutoffMillis = System.currentTimeMillis() - staleReservedThreshold.toMillis();
        Set<String> raw = redisTemplate.opsForZSet()
                .rangeByScore(RedisKeys.couponReserved(policyId), Double.NEGATIVE_INFINITY, cutoffMillis);
        if (raw == null || raw.isEmpty()) {
            return new HashSet<>();
        }
        Set<Long> result = new HashSet<>(raw.size());
        for (String value : raw) {
            result.add(Long.valueOf(value));
        }
        return result;
    }

    /** 대칭차집합 크기. FCFS 기대 상위 N명(expectedTopN) vs 실제 발급자 비교에 쓰인다. */
    private int countMismatch(Set<Long> dbUserIds, Set<Long> redisUserIds) {
        Set<Long> redisOnly = new HashSet<>(redisUserIds);
        redisOnly.removeAll(dbUserIds);
        Set<Long> dbOnly = new HashSet<>(dbUserIds);
        dbOnly.removeAll(redisUserIds);
        return redisOnly.size() + dbOnly.size();
    }

    /**
     * REDIS_ONLY 드리프트(Redis {@code issued} Set엔 있으나 DB {@code coupon_issue}엔 대응 행이
     * 없는 유저)를 {@code reconciliation_log}에 {@link ReconciliationType#ISSUE_REPROCESS}로
     * 등록한다.
     *
     * <p>Kafka 발행 자체가 실패한 경우는 이미 {@link KafkaCouponEventProducer#publishCouponIssuedEvent}가
     * {@link ReconciliationType#EVENT_REPUBLISH}로 잡아준다. 이 메서드가 메우는 구멍은 그보다 앞
     * 단계다 — Redis Lua가 재고 선점에 성공한 직후, Kafka 발행 시도(그 try 블록 진입)에도 이르지
     * 못하고 프로세스가 죽는 경로(kill/OOM 등)는 {@code recordPublishFailure}가 아예 호출될 기회가
     * 없어 지금까지 아무 흔적도 남기지 않았다 — 이 검증 배치가 다음 회차에 REDIS_ONLY로 발견하는
     * CSV 한 줄이 유일한 흔적이었고, 그 CSV를 사람이 열어보지 않으면 그 유저는 재고만 차감된 채
     * 영원히 아무것도 못 받았다.
     *
     * <p>{@link ReconciliationType#EVENT_REPUBLISH}가 아니라 {@code ISSUE_REPROCESS}로 등록하는
     * 이유: 재발행할 원본 {@code CouponIssuedEvent} payload가 없다(발급 시도가 Kafka 발행까지
     * 갔는지조차 알 수 없어 재구성할 근거가 없다). {@link ReconciliationAutoRetryScheduler}가
     * {@code ISSUE_REPROCESS}를 자동 재시도 대상에서 의도적으로 제외하는 것과 같은 이유로, 이
     * 상태는 사람이 실제 상태(재고 원복 vs 수동 발급)를 판단해야 한다 — 여기서는 등록(가시성
     * 확보)까지만 하고 자동으로 아무 것도 실행하지 않는다.
     *
     * <p>{@code eventKey}를 {@code policyId}·{@code userId}로 결정적으로 만들어, 같은 유저가
     * 다음 회차에도 여전히 REDIS_ONLY면 {@code existsByEventKey}에 걸려 중복 적재되지 않는다 —
     * 미해결 상태로 재처리 대기열에 한 행만 남는다.
     *
     * <p>여기 적재된 행은 {@code POST /api/admin/reconciliation/retry?logId=...}로 사람이
     * 명시적으로 눌렀을 때만 처리된다({@link ReconciliationRetryTrigger#dispatch} 참고).
     * 자동 재시도({@link ReconciliationAutoRetryScheduler})에는 일부러 넣지 않는다 — REDIS_ONLY는
     * "Redis가 맞고 DB가 틀렸다"고 단정할 수 없는 상태이기 때문이다(예: Redis 복구 배치가
     * DB 기준으로 재구성하기 전의 잔재라면, 자동 발급은 없어야 할 쿠폰을 만들어낸다).
     */
    /**
     * 존재 확인·적재를 나눠 실행할 청크 크기. IN 절 파라미터 수와 한 번에 영속성 컨텍스트에
     * 쌓이는 엔티티 수를 동시에 제한한다.
     */
    private static final int DRIFT_REGISTER_CHUNK_SIZE = 1000;

    /**
     * 발견한 드리프트를 {@code reconciliation_log}(ISSUE_REPROCESS)에 등록한다.
     *
     * <p><b>왜 건별이 아니라 청크 배치인가</b>: 예전에는 유저 한 명마다 {@code existsByEventKey}
     * SELECT 한 번 + {@code save()} 한 번 + ERROR 로그 한 줄을 찍었다. 미아 예약이 소수일 때는
     * 문제가 없었지만, 부하테스트 잔여물(reserved 1만 건)과 300만 건 데모 데이터의 상시 미아
     * 예약 1천 건이 겹치자 <b>11,000건 등록에 2분 넘게 걸리면서 스케줄러 스레드 하나를 통째로
     * 점유</b>했다(2026-08-31 실측: 15:03:23 → 15:05:22). {@code SchedulingConfig}의 풀에 여유가
     * 없던 탓에 그동안 {@code RedisAutoRecoveryScheduler.recoverMissingStock}이 밀렸고, 그 결과
     * 새로 만든 정책의 Redis 재고 키가 제때 초기화되지 않아 신규 발급 요청이 전량
     * {@code IllegalStateException}(500)으로 실패했다 — "발견만 하는 안전망"이 정작 정상 발급
     * 경로를 굶겨 죽인 셈이다.
     *
     * <p>그래서 (1) 존재 확인을 IN 조회 한 번으로 묶고, (2) 적재를 {@code saveAll}로 모으고,
     * (3) 건별 ERROR 로그를 요약 한 줄로 바꾼다. userId 오름차순으로 정렬해 적재하는 건
     * 동시에 도는 다른 쓰기와 락 획득 순서를 일정하게 맞추기 위함이다.
     */
    private void registerRedisOnlyDrift(
            Long policyId, Set<Long> driftUserIds, LocalDateTime detectedAt, String discrepancyType) {
        if (driftUserIds.isEmpty()) {
            return;
        }

        String keyPrefix = driftEventKeyPrefix(policyId, discrepancyType);

        List<Long> userIds = new ArrayList<>(driftUserIds);
        userIds.sort(null);

        int registered = 0;
        int alreadyRegistered = 0;
        int failed = 0;

        for (int from = 0; from < userIds.size(); from += DRIFT_REGISTER_CHUNK_SIZE) {
            List<Long> chunk = userIds.subList(
                    from, Math.min(from + DRIFT_REGISTER_CHUNK_SIZE, userIds.size()));

            List<String> eventKeys = chunk.stream().map(userId -> keyPrefix + userId).toList();
            Set<String> existing = reconciliationLogRepository.findExistingEventKeys(eventKeys);
            alreadyRegistered += existing.size();

            List<ReconciliationLog> toSave = new ArrayList<>();
            for (Long userId : chunk) {
                String eventKey = keyPrefix + userId;
                if (existing.contains(eventKey)) {
                    continue;
                }
                try {
                    String payload = objectMapper.writeValueAsString(
                            new RedisOnlyDriftDetail(policyId, userId, detectedAt));
                    ReconciliationLog reconciliationLog = new ReconciliationLog(
                            ReconciliationType.ISSUE_REPROCESS,
                            eventKey,
                            null,   // coupon_issue_id: DB에 대응 행이 없으므로 연결할 대상이 없다.
                            null,   // topic: 재발행할 Kafka 토픽이 없다(원본 이벤트를 모른다).
                            payload,
                            null    // requestedBy: 검증 배치가 자동으로 적재한 것이다.
                    );
                    reconciliationLog.recordOriginalFailure(reasonOf(discrepancyType, userId));
                    toSave.add(reconciliationLog);
                } catch (Exception e) {
                    // 여기서마저 실패하면 이 드리프트는 다음 회차 CSV로만 남는다 — 최소한 로그로는
                    // 추적 가능하게 CRITICAL로 남긴다(KafkaCouponEventProducer.recordPublishFailure와
                    // 동일한 이유). 이건 건별로 남긴다 — 요약만 남기면 어느 유저가 빠졌는지 알 수 없다.
                    failed++;
                    log.error("[Verification] CRITICAL: {} 드리프트를 reconciliation_log에 적재하지 못함 "
                                    + "- policyId={}, userId={}", discrepancyType, policyId, userId, e);
                }
            }

            if (!toSave.isEmpty()) {
                reconciliationLogRepository.saveAll(toSave);
                registered += toSave.size();
            }
        }

        if (registered > 0 || failed > 0) {
            log.error("🚨 정책 id={} {} 드리프트 {}건 신규 등록(이미 등록됨 {}건, 적재 실패 {}건) "
                            + "- reconciliation_log(ISSUE_REPROCESS)에 적재, 자동 재발급 없음",
                    policyId, discrepancyType, registered, alreadyRegistered, failed);
        }
    }

    private String reasonOf(String discrepancyType, Long userId) {
        if ("RESERVED_STALE".equals(discrepancyType)) {
            return "검증 배치가 감지한 미아 예약 — userId=" + userId + "가 재고를 선점한 채 임계 시간("
                    + staleReservedThreshold + ")을 넘도록 확정되지 않음(발급 이벤트 유실 추정). "
                    + "재처리하면 이 유저에게 쿠폰이 발급된다";
        }
        return "검증 배치가 감지한 REDIS_ONLY 드리프트 — Redis issued set에는 userId=" + userId
                + "가 있으나 coupon_issue에 대응 행이 없음. 재처리하면 이 유저에게 쿠폰이 발급된다";
    }


    /**
     * Redis 실시간 재고 카운터
     */
    private Integer readRedisStockCounter(Long policyId) {
        String value = redisTemplate.opsForValue().get(RedisKeys.couponStock(policyId));
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Check A: 재고 누수
     */
    private int computeStockLeakCount(
            Long policyId, int totalQuantity, int currentRedisStock, int dbIssuedCount, long totalReserved) {
        int totalReservedEver = totalQuantity - currentRedisStock;
        long confirmedOrPending = dbIssuedCount + totalReserved;
        long leak = totalReservedEver - confirmedOrPending;

        if (leak < 0) {
            log.warn("정책 id={} 재고 누수 계산에서 음수 발생: totalReservedEver={}, dbIssued={}, reserved={}",
                    policyId, totalReservedEver, dbIssuedCount, totalReserved);
            return 0;
        }
        if (leak > 0) {
            log.error("정책 id={} 재고 누수 의심: totalQuantity={}, currentRedisStock={}, dbIssued={}, "
                            + "reserved={}, leak={}건",
                    policyId, totalQuantity, currentRedisStock, dbIssuedCount, totalReserved, leak);
        }
        return (int) leak;
    }

    private List<CouponIssueLifecycleSnapshot> fetchLifecycleSnapshots(Long policyId) {
        return couponIssueRepository.findLifecycleSnapshotsByCouponPolicyId(policyId);
    }

    /** couponIssueId asc, id asc 순으로 오는 결과를 순회하며 마지막 값으로 덮어써 최신 이력만 남긴다. */
    private Map<Long, IssueStatus> fetchLatestHistoryStatusByIssueId(Long policyId) {
        List<CouponHistoryStatusSnapshot> snapshots =
                couponHistoryRepository.findStatusSnapshotsByCouponPolicyId(policyId);
        Map<Long, IssueStatus> latest = new HashMap<>();
        for (CouponHistoryStatusSnapshot snapshot : snapshots) {
            latest.put(snapshot.issueId(), snapshot.newStatus());
        }
        return latest;
    }

    /**
     * Check C: 선착순(FCFS) 검증용 — 대기열 순번(queue_rank=seq) 상위 N명. 호출부가 liveN을
     * totalQuantity로 이미 클램프하고 적재량도 확인한 뒤에만 부른다(performVerification() 참고).
     */
    private Set<Long> fetchExpectedTopN(Long policyId, int liveN) {
        if (liveN <= 0) {
            return Set.of();
        }
        return new HashSet<>(queueJoinLogRepository.findUserIdsOrderByQueueRankAsc(
                policyId, PageRequest.of(0, liveN)));
    }

    /**
     * Check B: 생명주기 불일치.
     */
    private List<LifecycleAnomaly> detectLifecycleAnomalies(
            Long policyId,
            List<CouponIssueLifecycleSnapshot> snapshots,
            Map<Long, IssueStatus> latestHistoryStatusByIssueId
    ) {
        List<LifecycleAnomaly> anomalies = new ArrayList<>();
        for (CouponIssueLifecycleSnapshot snapshot : snapshots) {
            IssueStatus latestHistoryStatus = latestHistoryStatusByIssueId.get(snapshot.issueId());
            if (latestHistoryStatus != null) {
                if (latestHistoryStatus != snapshot.status()) {
                    anomalies.add(new LifecycleAnomaly(snapshot.issueId(), snapshot.userId(), "HISTORY_MISMATCH"));
                }
                continue;
            }
            boolean hasTransitionEvidence = snapshot.status() != IssueStatus.ISSUED || snapshot.usedAt() != null;
            if (hasTransitionEvidence) {
                anomalies.add(new LifecycleAnomaly(snapshot.issueId(), snapshot.userId(), "MISSING_HISTORY"));
            }
        }
        if (!anomalies.isEmpty()) {
            log.error("정책 id={} 생명주기 불일치 {}건 발견(coupon_history vs coupon_issue.status)",
                    policyId, anomalies.size());
        }
        return anomalies;
    }

    /** 생명주기 불일치 1건. type은 "HISTORY_MISMATCH" 또는 "MISSING_HISTORY". */
    public record LifecycleAnomaly(Long issueId, Long userId, String type) {
    }

    /** performVerification()에서 계산한 불일치 결과를 CSV 작성기로 넘기기 위한 묶음. */
    public record MismatchFindings(
            Set<Long> dbUserIds,
            Set<Long> redisUserIds,
            int overIssuedCount,
            int stockLeakCount,
            List<LifecycleAnomaly> lifecycleAnomalies,
            /** Check C(FCFS): 대기열 도착 순서 상위 N명(이론상 당첨자). dbUserIds와 비교해 CSV에 반영한다. */
            Set<Long> expectedTopN,
            /** Check D: 임계 시간을 넘도록 reserved에 남아있는 유저(미아 예약). */
            Set<Long> staleReservedUserIds,
            /**
             * Check C가 실제로 수행됐는지(queue_join_log 적재분이 충분해서). false면 CSV
             * 작성기가 EXPECTED_NOT_ISSUED/ISSUED_NOT_EXPECTED를 절대 계산하지 않는다.
             *
             * <p>이 플래그가 없으면(실측으로 재현한 버그, 2026-08-30 — {@code
             * Docs/Verification-Batch-1M-Scale-Test.md}가 2026-08-27에 "고쳤다"고 기록했지만
             * 실제로는 반영된 적이 없었다) — Check C가 스킵돼 {@code expectedTopN}이 그냥 빈
             * Set이 되면, {@code dbUserIds - expectedTopN}(= dbUserIds 전체)이 전부
             * "ISSUED_NOT_EXPECTED"(선착순 순번 밖인데 발급됨)로 CSV에 찍힌다 — "아직 FCFS
             * 검증을 안 했다"가 아니라 "발급자 전원이 선착순을 위반했다"는 가짜 증거가 남는다.
             * mismatchCount 집계 자체는 fcfsMismatchCount=0으로 정확했지만, CSV 상세 내역만
             * 오염되는 조용한 버그라 리포트 목록만 보는 사람은 못 알아챈다.
             */
            boolean fcfsChecked
    ) {
    }

    /** CSV 리포트 작성 전담. 파일 I/O를 위 로직에서 분리해 테스트하기 쉽게 한다. */
    @Component
    public static class MismatchReportWriter {

        private static final String HEADER = "policyId,userId,couponIssueId,discrepancyType,detectedAt\n";

        private final Path reportDir;

        public MismatchReportWriter(
                @Value("${app.verification.report-dir:reports}") String reportDir
        ) {
            this.reportDir = Path.of(reportDir);
        }

        public Path write(Long policyId, LocalDateTime runAt, MismatchFindings findings) {
            Set<Long> redisOnly = new HashSet<>(findings.redisUserIds());
            redisOnly.removeAll(findings.dbUserIds());
            Set<Long> dbOnly = new HashSet<>(findings.dbUserIds());
            dbOnly.removeAll(findings.redisUserIds());

            StringBuilder csv = new StringBuilder(HEADER);
            appendUserRows(csv, policyId, redisOnly, "REDIS_ONLY", runAt);
            appendUserRows(csv, policyId, dbOnly, "DB_ONLY", runAt);
            if (findings.overIssuedCount() > 0) {
                // 특정 user_id/coupon_issue_id에 귀속되는 문제가 아니라 정책 전체의 집계 사실이라 비운다.
                appendPolicyLevelRow(csv, policyId, "OVERSOLD(+" + findings.overIssuedCount() + ')', runAt);
            }
            if (findings.stockLeakCount() > 0) {
                appendPolicyLevelRow(csv, policyId, "STOCK_LEAK(+" + findings.stockLeakCount() + ')', runAt);
            }
            appendLifecycleRows(csv, policyId, findings.lifecycleAnomalies(), runAt);

            // Check C(FCFS): 도착순 상위 N명(expectedTopN) vs 실제 DB 발급자(dbUserIds) 경계 비교.
            // fcfsChecked가 false(스킵됨)면 expectedTopN이 그냥 빈 Set이라, 여기서 계산을 그대로
            // 진행하면 dbUserIds 전원이 "ISSUED_NOT_EXPECTED"로 찍힌다 — "검증을 아직 안 했다"가
            // "발급자 전원이 선착순 위반"으로 둔갑하는 실측으로 재현된 버그(2026-08-30, 300만 건
            // 규모 시딩 도구로 발견). 스킵된 경우 두 유형 다 아예 계산하지 않는다.
            if (findings.fcfsChecked()) {
                Set<Long> expectedNotIssued = new HashSet<>(findings.expectedTopN());
                expectedNotIssued.removeAll(findings.dbUserIds());
                Set<Long> issuedNotExpected = new HashSet<>(findings.dbUserIds());
                issuedNotExpected.removeAll(findings.expectedTopN());
                appendUserRows(csv, policyId, expectedNotIssued, "EXPECTED_NOT_ISSUED", runAt);
                appendUserRows(csv, policyId, issuedNotExpected, "ISSUED_NOT_EXPECTED", runAt);
            }

            // Check D(미아 예약): 재고만 깎이고 발급이 확정되지 않은 유저.
            appendUserRows(csv, policyId, findings.staleReservedUserIds(), "RESERVED_STALE", runAt);

            try {
                Files.createDirectories(reportDir);
                Path file = reportDir.resolve("verification-%d-%d.csv".formatted(policyId, runAt.toInstant(
                        java.time.ZoneOffset.ofHours(9)).toEpochMilli()));
                Files.writeString(file, csv.toString());
                return file;
            } catch (IOException e) {
                throw new UncheckedIOException("검증 불일치 리포트(CSV) 작성 실패. policyId=" + policyId, e);
            }
        }

        private void appendUserRows(StringBuilder csv, Long policyId, Set<Long> userIds, String type, LocalDateTime runAt) {
            for (Long userId : userIds) {
                csv.append(policyId).append(',')
                        .append(userId).append(",,") // couponIssueId 칸은 비움
                        .append(type).append(',')
                        .append(runAt)
                        .append('\n');
            }
        }

        private void appendLifecycleRows(
                StringBuilder csv, Long policyId, List<LifecycleAnomaly> anomalies, LocalDateTime runAt) {
            for (LifecycleAnomaly anomaly : anomalies) {
                csv.append(policyId).append(',')
                        .append(anomaly.userId()).append(',')
                        .append(anomaly.issueId()).append(',')
                        .append(anomaly.type()).append(',')
                        .append(runAt)
                        .append('\n');
            }
        }

        /** userId/couponIssueId 둘 다 특정 대상이 없는 정책 단위 요약 행(OVERSOLD, STOCK_LEAK). */
        private void appendPolicyLevelRow(StringBuilder csv, Long policyId, String discrepancyType, LocalDateTime runAt) {
            csv.append(policyId).append(",,,")
                    .append(discrepancyType).append(',')
                    .append(runAt)
                    .append('\n');
        }

        /**
         * 다운로드용 경로 해석.
         */
        public Path resolveExistingFile(String storedReportUrl) {
            Path resolved;
            try {
                resolved = Path.of(storedReportUrl).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                // Path.of() 자체가 IllegalStateException이 아니라 InvalidPathException(IllegalArgumentException의
                // 하위 타입)을 던진다 — DB 값 오염을 방어하려는 이 메서드의 목적을 그대로 지키려면
                // 호출부가 잡는 예외 타입(IllegalStateException) 하나로 통일해서 다시 던져야 한다.
                throw new IllegalStateException("reportUrl이 올바른 경로 형식이 아닙니다: " + storedReportUrl, e);
            }
            Path root = reportDir.toAbsolutePath().normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalStateException(
                        "reportUrl이 reportDir(" + root + ") 밖을 가리킵니다: " + storedReportUrl);
            }
            // 이름 그대로 "존재하는" 파일만 돌려준다. 예전에는 경로 형식과 디렉터리 이탈만 검사해서,
            // 파일이 사라진 경우(컨테이너 재빌드로 reports 디렉터리가 날아가는 등) 호출부의
            // Files.readAllLines()에서 NoSuchFileException이 UncheckedIOException으로 새어나가
            // 500 INTERNAL_ERROR가 됐다. 준비돼 있던 VerificationReportFileMissingException(410 GONE)을
            // 제대로 타도록 여기서 걸러낸다.
            if (!Files.isRegularFile(resolved) || !Files.isReadable(resolved)) {
                throw new IllegalStateException(
                        "reportUrl이 가리키는 파일이 없거나 읽을 수 없습니다: " + resolved);
            }
            return resolved;
        }
    }
}
