package com.ureca.myureca.service;

import com.ureca.myureca.dto.response.ScaleTestResponse;
import com.ureca.myureca.dto.response.ScaleTestResponse.ScenarioResult;
import com.ureca.myureca.repository.CouponPolicyRepository;
import com.ureca.myureca.repository.VerificationReportRepository;
import com.ureca.myureca.support.RedisKeys;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 300만 건 규모 정합성 검증 데모 — 관리자 화면에서 "딸깍"으로 대량 시딩·삭제·전체 검증 결과
 * 조회를 할 수 있게 하는 도구.
 *
 * <p><b>격리하지 않는다</b>: {@code Docs/scripts/seed-3m-clean-with-fcfs.sql}로 했던 것과 달리,
 * 이 서비스는 완전히 격리된 임시 컨테이너가 아니라 <b>지금 이 앱이 붙어있는 DB/Redis에 그대로
 * 시딩</b>한다. 프론트 버튼 하나로 트리거되려면 격리된 인프라를 새로 띄울 수 없기 때문이다
 * (그러려면 앱이 Docker 소켓 권한을 가져야 하는데, 그건 이 도구의 범위를 넘는 별개의 위험이다).
 * 그 대신 {@link #delete()}로 완전히 되돌릴 수 있게 만들었다 — 시딩한 정책·유저·Redis 키를
 * 전부 식별 가능한 접두어({@code scale-3m-}, {@code scaletest-user-})로 표시해서, 실제
 * 부하테스트 데이터(정책 제목, {@code seed-user-} 접두어)와 절대 안 섞인다.
 *
 * <p><b>시나리오 5개, 합계 300만 건</b> — 정상 케이스 하나(대부분의 물량을 담당)와 정합성
 * 검증이 실제로 잡아내는 대표 불일치 유형 4가지를 하나씩 재현한다. 각 시나리오가 어떤 유형을
 * 만드는지는 {@link #seed()}의 각 private 메서드 주석 참고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScaleTestService {

    /** 이 도구가 만든 정책은 전부 이 접두어로 시작한다 — 상태 조회·삭제 모두 이 접두어로 찾는다. */
    public static final String TITLE_PREFIX = "scale-3m-";
    private static final String USER_EMAIL_PREFIX = "scaletest-user-";

    /** 0~9 열 행 10개짜리 파생 테이블 — 자릿수 크로스조인 숫자 생성기의 building block.
     *  {@code String.formatted(alias)}로 alias만 바꿔 여러 개 붙인다. */
    private static final String DIGIT_TABLE_SQL =
            "(select 0 n union all select 1 union all select 2 union all select 3 union all select 4 "
                    + "union all select 5 union all select 6 union all select 7 union all select 8 "
                    + "union all select 9) %s";

    // 시나리오별 크기. coupon_issue 합계는 여전히 정확히 300만 건이지만, 유저는 100만 명
    // 풀 하나를 여러 정책이 겹쳐서 나눠 쓴다(평균 유저 1명당 3건 발급 — "유저 100만 명에
    // 발급 이력 300만 건" 요구사항, 2026-08-30). CLEAN 하나만 867,000건×3정책으로 쪼갠 이유:
    // 유저 풀이 100만 명뿐이라 정책 하나가 100만 건보다 많은 유저를 필요로 할 수 없다
    // (coupon_issue는 (policy_id, user_id) 유니크라 한 정책 안에서 유저가 중복될 수 없음).
    // CLEAN만 2,601,000건이라 유일하게 풀 크기를 넘어서므로 3정책으로 나눴고, 나머지 4개는
    // 전부 10만 건 안팎이라 풀 안에 그대로 들어간다.
    private static final int POOL_SIZE = 1_000_000;

    private static final int CLEAN_POLICY_SIZE = 867_000; // 867,000 × 3 = 2,601,000
    private static final int OVERSOLD_TOTAL_QUANTITY = 99_500;
    private static final int OVERSOLD_ISSUED = 100_000; // 재고보다 500건 더 발급됨
    private static final int OVERSOLD_REDIS_ONLY_EXTRA = 300;
    private static final int LIFECYCLE_SIZE = 100_000;
    private static final int LIFECYCLE_MISSING_HISTORY = 1_000;
    private static final int RESERVED_STALE_ISSUED = 99_000;
    private static final int RESERVED_STALE_GHOST = 1_000; // reserved ZSET에만 있고 DB엔 없는 유령
    private static final int FCFS_ISSUED = 100_000;
    private static final int FCFS_GHOST_FRONTRUNNER = 1_000; // 대기열 앞줄인데 발급 못 받은 유령

    // 각 정책이 100만 명 풀 안에서 잘라 쓰는 구간(offset, 0-based). 겹치도록 일부러 배치했다
    // (예: OVERSOLD/LIFECYCLE/RESERVED_STALE/FCFS 구간 전부 CLEAN 세 정책의 합집합 [0,999999]
    // 안에 포함) — 그래야 유저가 여러 정책에 걸쳐 실제로 재사용된다. 각 구간은 POOL_SIZE를
    // 넘지 않게 잡아서(끝 offset < POOL_SIZE) 래핑 없이 단순 연속 구간으로 처리한다.
    private static final long CLEAN_1_OFFSET = 0;
    private static final long CLEAN_2_OFFSET = 66_500;
    private static final long CLEAN_3_OFFSET = 133_000; // 133,000 + 867,000 - 1 = 999,999(꽉 참)
    private static final long OVERSOLD_OFFSET = 200_000;
    private static final long LIFECYCLE_OFFSET = 400_000;
    private static final long RESERVED_STALE_OFFSET = 600_000;
    private static final long FCFS_OFFSET = 800_000; // 800,000 + 101,000 - 1 = 900,999

    @PersistenceContext
    private EntityManager entityManager;

    private final CouponPolicyRepository couponPolicyRepository;
    private final VerificationReportRepository verificationReportRepository;
    private final VerificationService verificationService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 이미 시딩된 게 있으면 먼저 지우고(멱등) 처음부터 다시 만든다. 트랜잭션 하나로 묶지 않는다
     * — 300만 건 INSERT를 단일 트랜잭션(undo 로그)으로 묶으면 롤백 세그먼트 부담이 커지고,
     * 어차피 실패 시 {@link #delete()}로 걷어내는 게 더 안전하다(부분 성공 상태를 트랜잭션
     * 롤백에 맡기지 않고 명시적으로 처리).
     */
    @Transactional
    public ScaleTestResponse seed() {
        // delete()와 이 메서드가 각자 따로 체크를 껐다 켜면, delete() 안의 finally가 seed()의
        // 나머지 INSERT 구간에 대해서까지 체크를 도로 켜버린다 — 그래서 여기서 한 번만 끄고
        // 끝까지(삭제+생성 전체) 유지한 뒤, 이 메서드가 끝날 때만 원복한다.
        disableIntegrityChecks();
        try {
            deleteInternal();

            log.info("[ScaleTest] 유저 {}명(풀) 시딩 시작", POOL_SIZE);
            long userMinId = seedUsers(POOL_SIZE);
            log.info("[ScaleTest] 유저 시딩 완료. baseId={}", userMinId);

            List<ScenarioResult> results = new ArrayList<>();
            results.add(seedClean(userMinId, CLEAN_1_OFFSET, "clean-1"));
            results.add(seedClean(userMinId, CLEAN_2_OFFSET, "clean-2"));
            results.add(seedClean(userMinId, CLEAN_3_OFFSET, "clean-3"));
            results.add(seedOversoldRedisOnly(userMinId + OVERSOLD_OFFSET));
            results.add(seedLifecycleAnomaly(userMinId + LIFECYCLE_OFFSET));
            results.add(seedReservedStale(userMinId + RESERVED_STALE_OFFSET));
            results.add(seedFcfsViolation(userMinId + FCFS_OFFSET));

            long totalRows = results.stream().mapToLong(ScenarioResult::couponIssueRows).sum();
            log.info("[ScaleTest] 시딩 완료. 정책 {}개, coupon_issue 합계 {}건", results.size(), totalRows);
            resyncSequenceGenerators();
            return new ScaleTestResponse(results, totalRows, POOL_SIZE);
        } finally {
            enableIntegrityChecks();
        }
    }

    /**
     * {@code coupon_issue}/{@code coupon_history}는 네이티브 SQL로 직접 INSERT하기 때문에(위
     * {@link #insertCouponIssue}, {@link #insertCouponHistoryForAllIssued} 등) MySQL의
     * AUTO_INCREMENT 카운터만 올라갈 뿐, JPA {@code GenerationType.SEQUENCE}가 실제로 채번에
     * 쓰는 별도 카운터 테이블(각각 {@code coupon_issue_seq}/{@code coupon_history_seq})은 전혀
     * 갱신되지 않는다 — 이 두 채번기는 완전히 독립적이다.
     *
     * <p>이 상태에서 이 도구로 300만 건을 시딩하면 AUTO_INCREMENT는 300만대까지 올라가지만,
     * Hibernate 시퀀스 카운터는 시딩 전 수준(수만 이하)에 그대로 머문다. 그 직후 실제 발급
     * 플로우(Kafka 컨슈머의 {@code CouponIssueRepository.save()})가 이 낮은 카운터에서 ID를
     * 받아오면, 그 ID는 이미 방금 시딩한 더미 데이터가 점유하고 있어 매번
     * {@code Duplicate entry 'N' for key 'coupon_issue.PRIMARY'}로 INSERT가 실패한다 — 겉으로는
     * 컨슈머의 "인박스 체크(멱등성)"가 중복으로 걸러낸 것처럼 로그가 찍히지만, 실제로는 전혀
     * 다른 원인(PK 충돌)으로 매 건이 진짜 실패하는 것이라 Redis {@code reserved}만 쌓이고
     * DB엔 아무것도 안 남는다(실측, 2026-08-31).
     *
     * <p>시딩 직후 두 카운터를 실제 MAX(id)+1 이상으로 강제로 맞춰서 이 충돌을 원천 차단한다.
     * {@code WHERE s.next_val < m.next_id}로 걸어서, 이미 더 앞서 있는 카운터를 실수로 뒤로
     * 되돌리지는 않는다(단조 증가만 허용).
     */
    private void resyncSequenceGenerators() {
        int issueBumped = entityManager.createNativeQuery(
                        "update coupon_issue_seq s "
                                + "join (select coalesce(max(id), 0) + 1 as next_id from coupon_issue) m "
                                + "set s.next_val = m.next_id "
                                + "where s.next_val < m.next_id")
                .executeUpdate();
        int historyBumped = entityManager.createNativeQuery(
                        "update coupon_history_seq s "
                                + "join (select coalesce(max(id), 0) + 1 as next_id from coupon_history) m "
                                + "set s.next_val = m.next_id "
                                + "where s.next_val < m.next_id")
                .executeUpdate();
        if (issueBumped > 0 || historyBumped > 0) {
            log.info("[ScaleTest] Hibernate 시퀀스 카운터를 실제 MAX(id) 이상으로 재동기화 완료 "
                    + "(coupon_issue_seq bumped={}, coupon_history_seq bumped={}) — "
                    + "이후 실제 발급 플로우의 PK 충돌을 방지", issueBumped, historyBumped);
        }
    }

    /**
     * 존재하는 정책 전부(이 도구가 만든 것 + 기존 부하테스트 등 다른 정책 전부)에 대해 순차로
     * 정합성 검증(force=true)을 실행하고, 각 리포트가 끝날 때까지 기다렸다가 결과를 모아서
     * 돌려준다. {@code performVerification}은 비동기라 폴링한다.
     *
     * <p><b>삭제({@link #delete()})와 스코프가 다르다</b> — 검증은 "지금 존재하는 모든 정책의
     * 정합성을 한 번에 확인하고 싶다"는 요구라 전체를 대상으로 하지만, 삭제는 실수로 실제
     * 데이터를 지우면 안 되므로 이 도구가 만든 {@code scale-3m-*} 정책만 계속 좁혀서 다룬다
     * (2026-08-31 요구사항: "검증은 같이 하고 삭제는 300만 건 더미 데이터만").
     */
    public ScaleTestResponse verifyAll() {
        List<com.ureca.myureca.domain.coupon.CouponPolicy> policies = allPolicies();
        if (policies.isEmpty()) {
            throw new IllegalStateException("존재하는 정책이 없습니다.");
        }

        List<ScenarioResult> results = new ArrayList<>();
        for (com.ureca.myureca.domain.coupon.CouponPolicy policy : policies) {
            verificationService.runVerification(policy.getId(), true);
            var report = waitForReport(policy.getId());
            long rows = countCouponIssue(policy.getId());
            results.add(new ScenarioResult(
                    policy.getId(), policy.getTitle(), scenarioTypeOf(policy.getTitle()),
                    scenarioDescriptionOf(policy.getTitle()), policy.getTotalQuantity(), rows,
                    report.getStatus().name(), report.getMismatchCount()));
        }
        long totalRows = results.stream().mapToLong(ScenarioResult::couponIssueRows).sum();
        return new ScaleTestResponse(results, totalRows, POOL_SIZE);
    }

    /**
     * 검증을 새로 실행하지 않고, 지금까지 쌓인 최신 리포트 상태만 조회. {@link #verifyAll()}과
     * 스코프를 맞춰 존재하는 정책 전부를 대상으로 한다 — 안 그러면 verifyAll이 검증한
     * 기존 정책들의 결과가 이 화면 표에서 안 보이게 된다.
     */
    @Transactional(readOnly = true)
    public ScaleTestResponse status() {
        List<com.ureca.myureca.domain.coupon.CouponPolicy> policies = allPolicies();
        List<ScenarioResult> results = new ArrayList<>();
        for (com.ureca.myureca.domain.coupon.CouponPolicy policy : policies) {
            var latest = verificationReportRepository
                    .findFirstByCouponPolicy_IdOrderByIdDesc(policy.getId()).orElse(null);
            long rows = countCouponIssue(policy.getId());
            results.add(new ScenarioResult(
                    policy.getId(), policy.getTitle(), scenarioTypeOf(policy.getTitle()),
                    scenarioDescriptionOf(policy.getTitle()), policy.getTotalQuantity(), rows,
                    latest == null ? null : latest.getStatus().name(),
                    latest == null ? null : latest.getMismatchCount()));
        }
        long totalRows = results.stream().mapToLong(ScenarioResult::couponIssueRows).sum();
        return new ScaleTestResponse(results, totalRows, POOL_SIZE);
    }

    /**
     * 이 도구가 만든 정책·유저·Redis 키를 전부 지운다. FK 순서: coupon_issue를 먼저 지우면
     * coupon_history는 {@code ON DELETE CASCADE}로 같이 지워진다. queue_join_log는 FK가 없어
     * 순서 무관. 유저는 coupon_issue가 먼저 없어져야(fk_issue_user) 지울 수 있다.
     */
    @Transactional
    public void delete() {
        disableIntegrityChecks();
        try {
            deleteInternal();
        } finally {
            enableIntegrityChecks();
        }
    }

    /**
     * 존재하는(소프트 삭제 안 된) 정책 전부를 id 오름차순으로 — {@link #verifyAll()}/{@link #status()}
     * 전용. {@link #deleteInternal()}은 일부러 이 메서드를 안 쓰고 {@code TITLE_PREFIX}로 계속
     * 좁혀서 찾는다(삭제는 이 도구가 만든 더미 데이터만 건드려야 하므로).
     */
    private List<com.ureca.myureca.domain.coupon.CouponPolicy> allPolicies() {
        return couponPolicyRepository.findByDeletedAtIsNull().stream()
                .sorted(java.util.Comparator.comparing(com.ureca.myureca.domain.coupon.CouponPolicy::getId))
                .toList();
    }

    /**
     * 실제 삭제 로직. 무결성 체크 on/off는 호출부 책임 — {@link #delete()}(단독 호출)와
     * {@link #seed()}(삭제 후 바로 재시딩)가 각자 다른 범위로 껐다 켜야 해서 여기서는
     * 관여하지 않는다.
     *
     * <p>실측(2026-08-30): 이 5개 정책이 coupon_issue 테이블 전체 행의 대부분을 차지하는
     * 상황에서는 옵티마이저가 인덱스 대신 풀 스캔을 택하고(선택도가 나빠서 그게 더 빠름),
     * 거기에 coupon_history의 ON DELETE CASCADE(행 단위로 처리됨, InnoDB가 느림)까지 겹쳐
     * 12분 넘게 걸리는 걸 확인했다. coupon_history를 JOIN 기반으로 먼저 직접 지워서(MySQL의
     * 내부 캐스케이드 메커니즘보다 진짜 집합 연산이 빠르다) 이 비용을 줄인다 —
     * foreign_key_checks=0(호출부가 켜둠) 덕분에 coupon_issue 삭제 시 자식을 다시 찾아
     * 캐스케이드하려는 절차 자체도 건너뛴다.
     */
    private void deleteInternal() {
        List<com.ureca.myureca.domain.coupon.CouponPolicy> policies =
                couponPolicyRepository.findByTitleStartingWithOrderByIdAsc(TITLE_PREFIX);
        if (policies.isEmpty()) {
            return;
        }
        List<Long> policyIds = policies.stream().map(com.ureca.myureca.domain.coupon.CouponPolicy::getId).toList();
        log.info("[ScaleTest] 기존 시딩 {}개 정책 삭제 시작", policyIds.size());

        entityManager.createNativeQuery(
                        "delete ch from coupon_history ch "
                                + "join coupon_issue ci on ci.id = ch.coupon_issue_id "
                                + "where ci.coupon_policy_id in (:ids)")
                .setParameter("ids", policyIds)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "delete from coupon_issue where coupon_policy_id in (:ids)")
                .setParameter("ids", policyIds)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "delete from queue_join_log where coupon_policy_id in (:ids)")
                .setParameter("ids", policyIds)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "delete from verification_report where coupon_policy_id in (:ids)")
                .setParameter("ids", policyIds)
                .executeUpdate();
        entityManager.createNativeQuery("delete from coupon_policy where id in (:ids)")
                .setParameter("ids", policyIds)
                .executeUpdate();
        entityManager.createNativeQuery("delete from users where email like :prefix")
                .setParameter("prefix", USER_EMAIL_PREFIX + "%")
                .executeUpdate();

        for (Long policyId : policyIds) {
            deleteRedisKeysFor(policyId);
        }
        log.info("[ScaleTest] 삭제 완료");
    }

    /**
     * {@code unique_checks}/{@code foreign_key_checks}를 끈다 — 이 도구가 만드는 데이터는
     * 생성 시점부터 서로 충돌하지 않게 설계돼 있고(겹치지 않는 id 구간) FK 대상도 항상 먼저
     * 만들어두므로, 매 행마다의 무결성 검사를 건너뛰어도 안전하다. 세션 변수라 커넥션 풀에
     * 새지 않도록 반드시 {@link #enableIntegrityChecks()}와 짝을 맞춰(try/finally) 쓴다.
     */
    private void disableIntegrityChecks() {
        entityManager.createNativeQuery("set session foreign_key_checks = 0").executeUpdate();
        entityManager.createNativeQuery("set session unique_checks = 0").executeUpdate();
    }

    private void enableIntegrityChecks() {
        entityManager.createNativeQuery("set session foreign_key_checks = 1").executeUpdate();
        entityManager.createNativeQuery("set session unique_checks = 1").executeUpdate();
    }

    // ─── 유저 벌크 시딩 ──────────────────────────────────────────────────────

    /**
     * 재귀 CTE(1..total)로 숫자 생성기를 만들어 유저 N명을 한 번에 INSERT한다(개별 save() 300만
     * 번은 비현실적으로 느림). {@code scripts/seed-users.sql}(20,000명 부하테스트 유저 시딩)과
     * 같은 패턴 — 그 스크립트가 이미 이 방식을 쓰고 있어서 검증된 접근이다.
     *
     * @return 방금 만든 유저들의 최소 id(이 뒤로 total명만큼 연속된다고 가정 — 단일 INSERT라
     *         AUTO_INCREMENT가 끊기지 않는 한 안전)
     */
    private long seedUsers(int total) {
        // 두 가지를 실측으로 걸러내고 정착한 방식이다.
        //   1) information_schema.columns 셀프 크로스조인: 시스템 테이블 개수에 기대는 방식이라
        //      환경에 따라 필요한 행 수를 못 채운다(3,002,000명 요청 시 683,929명만 생성됨 — 실측).
        //   2) 재귀 CTE(WITH RECURSIVE): 개수는 항상 정확했지만, MySQL이 INSERT...SELECT 안에서
        //      재귀 CTE를 실행할 때는(단독 SELECT일 때와 달리) 내부적으로 한 행씩 처리하는 것으로
        //      보여 300만 건 기준 100초~14분까지 실행 시간이 크게 들쭉날쭉했다(실측).
        // 지금 쓰는 방식(자릿수 크로스조인, "숫자 생성기"의 표준 기법): 10행짜리 테이블 7개를
        // 곱해 10^7=1,000만 조합을 순수 JOIN(재귀도 시스템 테이블 의존도 없음)으로 만든 뒤 필요한
        // 만큼만 자른다 — 시스템 상태와 무관하게 항상 정확한 개수이고, 실측상 재귀 CTE보다 훨씬
        // 빠르고 일정하다(300만 건 INSERT 실측 1분 2초, 여러 번 재현해도 편차 거의 없음).
        // unique_checks=0(disableIntegrityChecks로 seed() 전체에 걸쳐 이미 꺼져있음): users.email에
        // 유니크 인덱스가 있는데, 이 생성기가 만드는 값은 이미 서로 겹치지 않는다는 걸 알고
        // 있으므로 매 행마다의 유니크 검사를 건너뛰어 쓰기 비용을 줄인다.
        entityManager.createNativeQuery(
                        "insert into users (email, name, created_at, updated_at) "
                                + "select concat(:prefix, n, '@test.com'), concat(:prefix, n), now(), now() "
                                + "from ("
                                + "  select (d1.n + d2.n*10 + d3.n*100 + d4.n*1000 + d5.n*10000 "
                                + "          + d6.n*100000 + d7.n*1000000 + 1) as n "
                                + "  from " + DIGIT_TABLE_SQL.formatted("d1") + ", " + DIGIT_TABLE_SQL.formatted("d2")
                                + ", " + DIGIT_TABLE_SQL.formatted("d3") + ", " + DIGIT_TABLE_SQL.formatted("d4")
                                + ", " + DIGIT_TABLE_SQL.formatted("d5") + ", " + DIGIT_TABLE_SQL.formatted("d6")
                                + ", " + DIGIT_TABLE_SQL.formatted("d7")
                                + ") t "
                                + "where n <= :total")
                .setParameter("prefix", USER_EMAIL_PREFIX)
                .setParameter("total", total)
                .executeUpdate();

        Object minId = entityManager.createNativeQuery(
                        "select min(id) from users where email like :prefix")
                .setParameter("prefix", USER_EMAIL_PREFIX + "%")
                .getSingleResult();
        return ((Number) minId).longValue();
    }

    // ─── 시나리오 1: 완전 일치(정상, 대부분의 물량) ────────────────────────────

    /**
     * DB·Redis·대기열로그 전부 정확히 일치 — SUCCESS 기대. 100만 유저 풀 중 이 정책이 쓰는
     * offset 구간({@code CLEAN_1/2/3_OFFSET})은 서로 겹치도록 배치돼 있어서, 세 CLEAN 정책의
     * 합집합이 풀 전체([0, POOL_SIZE-1])를 덮는다 — "유저 100만 명 공유" 요구사항.
     */
    private ScenarioResult seedClean(long userMinId, long offset, String suffix) {
        long userFrom = userMinId + offset;
        Long policyId = insertPolicy(suffix, CLEAN_POLICY_SIZE);
        long userTo = userFrom + CLEAN_POLICY_SIZE - 1;

        insertCouponIssue(policyId, userFrom, userTo);
        insertCouponHistoryForAllIssued(policyId);
        insertQueueJoinLog(policyId, userFrom, userTo, 1);

        sadd(RedisKeys.couponIssued(policyId), userFrom, userTo);
        redisTemplate.opsForValue().set(RedisKeys.couponStock(policyId), "0");

        return result(policyId, "CLEAN", "완전 일치(정상) — SUCCESS 기대", CLEAN_POLICY_SIZE, CLEAN_POLICY_SIZE);
    }

    // ─── 시나리오 2: 초과발급 + REDIS_ONLY ─────────────────────────────────

    /** 재고보다 500건 더 발급(OVERSOLD) + Redis issued SET에만 있는 유령 300명(REDIS_ONLY). */
    private ScenarioResult seedOversoldRedisOnly(long userFrom) {
        Long policyId = insertPolicy("oversold-redisonly", OVERSOLD_TOTAL_QUANTITY);
        long userTo = userFrom + OVERSOLD_ISSUED - 1;

        insertCouponIssue(policyId, userFrom, userTo);
        insertCouponHistoryForAllIssued(policyId);
        // 이 시나리오는 FCFS(Check C)를 검증 대상에서 뺀다 — queue_join_log를 비워둠(정상 스킵).

        sadd(RedisKeys.couponIssued(policyId), userFrom, userTo);
        // REDIS_ONLY용 유령 300명 — DB엔 전혀 없는 userId(발급자 범위 밖의 아주 큰 값으로 충돌 방지)
        long ghostFrom = 900_000_000_000L;
        sadd(RedisKeys.couponIssued(policyId), ghostFrom, ghostFrom + OVERSOLD_REDIS_ONLY_EXTRA - 1);
        redisTemplate.opsForValue().set(RedisKeys.couponStock(policyId), "0");

        return result(policyId, "OVERSOLD_REDIS_ONLY",
                "초과발급 500건(재고 " + OVERSOLD_TOTAL_QUANTITY + " / 발급 " + OVERSOLD_ISSUED
                        + ") + Redis에만 있는 유령 " + OVERSOLD_REDIS_ONLY_EXTRA + "명(REDIS_ONLY)",
                OVERSOLD_TOTAL_QUANTITY, OVERSOLD_ISSUED);
    }

    // ─── 시나리오 3: 이력 누락(MISSING_HISTORY) ────────────────────────────

    /** DB·Redis는 완전 일치하지만 coupon_history 1,000건이 빠져 있음. */
    private ScenarioResult seedLifecycleAnomaly(long userFrom) {
        // detectLifecycleAnomalies는 status가 ISSUED이고 used_at도 없으면 "정상 최초 발급"으로
        // 본다 — 이력이 없어도 이상으로 안 잡는다(hasTransitionEvidence=false). MISSING_HISTORY로
        // 잡히려면 "USED로 전이된 증거(status=USED 또는 used_at 존재)는 있는데 그 전이를 설명하는
        // coupon_history가 없는" 상태여야 한다. 그래서 마지막 1,000명만 상태를 USED로 만들고
        // 이력은 아예 안 넣는다.
        Long policyId = insertPolicy("lifecycle-anomaly", LIFECYCLE_SIZE);
        long normalTo = userFrom + LIFECYCLE_SIZE - LIFECYCLE_MISSING_HISTORY - 1;
        long anomalyFrom = normalTo + 1;
        long userTo = userFrom + LIFECYCLE_SIZE - 1;

        insertCouponIssue(policyId, userFrom, normalTo);
        insertCouponHistoryForAllIssued(policyId);
        insertCouponIssueUsedWithoutHistory(policyId, anomalyFrom, userTo);

        sadd(RedisKeys.couponIssued(policyId), userFrom, userTo);
        redisTemplate.opsForValue().set(RedisKeys.couponStock(policyId), "0");

        return result(policyId, "MISSING_HISTORY",
                "발급/재고는 완전 일치하지만 " + LIFECYCLE_MISSING_HISTORY
                        + "명이 USED로 전이된 증거는 있는데 그 이력(coupon_history)이 없음",
                LIFECYCLE_SIZE, LIFECYCLE_SIZE);
    }

    // ─── 시나리오 4: 미아 예약(RESERVED_STALE) ─────────────────────────────

    /** 정상 발급 99,000건 + reserved ZSET에만 있고 DB엔 없는 유령 1,000명(오래된 timestamp). */
    private ScenarioResult seedReservedStale(long userFrom) {
        Long policyId = insertPolicy("reserved-stale", 100_000);
        long issuedTo = userFrom + RESERVED_STALE_ISSUED - 1;
        long ghostFrom = issuedTo + 1;
        long ghostTo = ghostFrom + RESERVED_STALE_GHOST - 1;

        insertCouponIssue(policyId, userFrom, issuedTo);
        insertCouponHistoryForAllIssued(policyId);

        sadd(RedisKeys.couponIssued(policyId), userFrom, issuedTo);
        // 임계 시간(app.verification.stale-reserved-threshold, 기본 5분)을 넉넉히 넘긴 시각으로 등록.
        long staleScore = System.currentTimeMillis() - java.time.Duration.ofHours(1).toMillis();
        zaddRange(RedisKeys.couponReserved(policyId), ghostFrom, ghostTo, staleScore);
        redisTemplate.opsForValue().set(RedisKeys.couponStock(policyId), String.valueOf(100_000 - RESERVED_STALE_ISSUED));

        return result(policyId, "RESERVED_STALE",
                "정상 발급 " + RESERVED_STALE_ISSUED + "건 + reserved ZSET에만 남은 미아 예약 "
                        + RESERVED_STALE_GHOST + "명(임계시간 초과, DB엔 없음)",
                100_000, RESERVED_STALE_ISSUED);
    }

    // ─── 시나리오 5: 선착순(FCFS) 역전 ──────────────────────────────────────

    /**
     * 발급 10만 건은 완전 정상(개수·저장소 다 일치)이지만, 대기열 순번(queue_rank)이 실제
     * 발급자와 어긋나 있다 — 뒤늦게 합류한 유령 1,000명이 맨 앞 순번을 차지하고, 정작 발급받은
     * 사람 중 1,000명은 그 뒤로 밀려나 있다. EXPECTED_NOT_ISSUED 1,000 + ISSUED_NOT_EXPECTED
     * 1,000(정확히 짝을 이룸 — 실제 운영 중 관찰되는 FCFS 역전과 같은 패턴).
     */
    private ScenarioResult seedFcfsViolation(long userFrom) {
        Long policyId = insertPolicy("fcfs-violation", FCFS_ISSUED);
        long issuedTo = userFrom + FCFS_ISSUED - 1; // 실제 발급자: [userFrom, issuedTo]
        long ghostFrom = issuedTo + 1;
        long ghostTo = ghostFrom + FCFS_GHOST_FRONTRUNNER - 1; // 발급 못 받은 유령 선두주자

        insertCouponIssue(policyId, userFrom, issuedTo);
        insertCouponHistoryForAllIssued(policyId);
        sadd(RedisKeys.couponIssued(policyId), userFrom, issuedTo);
        redisTemplate.opsForValue().set(RedisKeys.couponStock(policyId), "0");

        // 대기열 로그: 발급자 100,000명 + 유령 1,000명, 합계 101,000행을 rank 1..101,000에
        // 빈틈없이 배정한다(발급자 전원이 반드시 대기열 로그를 가져야 한다 — 안 그러면
        // coveredCount < liveN이 돼서 Check C 자체가 스킵돼버린다, 실측으로 확인한 버그).
        //   rank 1..1,000       = 유령(ghost)                — 순번 안인데 발급 못 받음
        //   rank 1,001..100,000 = 발급자 중 앞 99,000명(정상) — 순번과 발급 결과 일치
        //   rank 100,001..101,000 = 발급자 중 뒤 1,000명(victim) — 순번 밖으로 밀려남
        long victimFrom = issuedTo - FCFS_GHOST_FRONTRUNNER + 1; // 발급자 중 마지막 1,000명
        long normalTo = victimFrom - 1; // 발급자 중 앞 99,000명의 끝
        entityManager.createNativeQuery(
                        "insert into queue_join_log (coupon_policy_id, user_id, status, queue_rank, joined_at, created_at) "
                                + "select :policyId, id, 'ADMITTED', (id - :ghostFrom + 1), now(), now() "
                                + "from users where id between :ghostFrom and :ghostTo")
                .setParameter("policyId", policyId).setParameter("ghostFrom", ghostFrom).setParameter("ghostTo", ghostTo)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "insert into queue_join_log (coupon_policy_id, user_id, status, queue_rank, joined_at, created_at) "
                                + "select :policyId, id, 'ADMITTED', (id - :userFrom + 1 + :ghostCount), now(), now() "
                                + "from users where id between :userFrom and :normalTo")
                .setParameter("policyId", policyId).setParameter("userFrom", userFrom)
                .setParameter("normalTo", normalTo).setParameter("ghostCount", FCFS_GHOST_FRONTRUNNER)
                .executeUpdate();
        entityManager.createNativeQuery(
                        "insert into queue_join_log (coupon_policy_id, user_id, status, queue_rank, joined_at, created_at) "
                                + "select :policyId, id, 'ADMITTED', (id - :victimFrom + 1 + :cutoff), now(), now() "
                                + "from users where id between :victimFrom and :issuedTo")
                .setParameter("policyId", policyId).setParameter("victimFrom", victimFrom)
                .setParameter("issuedTo", issuedTo).setParameter("cutoff", (long) FCFS_ISSUED)
                .executeUpdate();

        return result(policyId, "FCFS_VIOLATION",
                "발급 " + FCFS_ISSUED + "건은 완전 정상이지만 대기열 순번이 어긋남 — 순번 안인데 못"
                        + " 받음 " + FCFS_GHOST_FRONTRUNNER + "건 / 순번 밖인데 받음 "
                        + FCFS_GHOST_FRONTRUNNER + "건",
                FCFS_ISSUED, FCFS_ISSUED);
    }

    // ─── 공용 헬퍼 ──────────────────────────────────────────────────────────

    private Long insertPolicy(String scenarioSuffix, int totalQuantity) {
        var policy = new com.ureca.myureca.domain.coupon.CouponPolicy(
                TITLE_PREFIX + scenarioSuffix, com.ureca.myureca.domain.coupon.CouponType.FIXED, 1000,
                totalQuantity, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusDays(1));
        entityManager.persist(policy);
        entityManager.flush();
        return policy.getId();
    }

    private void insertCouponIssue(Long policyId, long userFrom, long userTo) {
        entityManager.createNativeQuery(
                        "insert into coupon_issue (coupon_policy_id, user_id, receipt_id, status, issued_at, created_at, updated_at) "
                                + "select :policyId, id, concat('rcpt_scale_', :policyId, '_', id), 'ISSUED', now(), now(), now() "
                                + "from users where id between :userFrom and :userTo")
                .setParameter("policyId", policyId).setParameter("userFrom", userFrom).setParameter("userTo", userTo)
                .executeUpdate();
    }

    private void insertCouponHistoryForAllIssued(Long policyId) {
        entityManager.createNativeQuery(
                        "insert into coupon_history (coupon_issue_id, request_id, prev_status, new_status, created_at) "
                                + "select id, receipt_id, 'NONE', 'ISSUED', issued_at "
                                + "from coupon_issue where coupon_policy_id = :policyId")
                .setParameter("policyId", policyId)
                .executeUpdate();
    }

    /**
     * status=USED(+used_at 존재)로 발급 행을 만들되 coupon_history는 아예 안 넣는다 —
     * {@code detectLifecycleAnomalies}가 "USED로 전이된 증거는 있는데 그 전이를 설명하는
     * 이력이 없다"고 판단해 MISSING_HISTORY로 잡도록 만드는 시나리오 전용 헬퍼.
     */
    private void insertCouponIssueUsedWithoutHistory(Long policyId, long userFrom, long userTo) {
        entityManager.createNativeQuery(
                        "insert into coupon_issue (coupon_policy_id, user_id, receipt_id, status, issued_at, used_at, created_at, updated_at) "
                                + "select :policyId, id, concat('rcpt_scale_', :policyId, '_', id), 'USED', now(), now(), now(), now() "
                                + "from users where id between :userFrom and :userTo")
                .setParameter("policyId", policyId).setParameter("userFrom", userFrom).setParameter("userTo", userTo)
                .executeUpdate();
    }

    private void insertQueueJoinLog(Long policyId, long userFrom, long userTo, long rankBase) {
        entityManager.createNativeQuery(
                        "insert into queue_join_log (coupon_policy_id, user_id, status, queue_rank, joined_at, created_at) "
                                + "select :policyId, id, 'ADMITTED', (id - :userFrom + :rankBase), now(), now() "
                                + "from users where id between :userFrom and :userTo")
                .setParameter("policyId", policyId).setParameter("userFrom", userFrom).setParameter("userTo", userTo)
                .setParameter("rankBase", rankBase)
                .executeUpdate();
    }

    private long countCouponIssue(Long policyId) {
        Object count = entityManager.createNativeQuery(
                        "select count(*) from coupon_issue where coupon_policy_id = :policyId")
                .setParameter("policyId", policyId)
                .getSingleResult();
        return ((Number) count).longValue();
    }

    private com.ureca.myureca.domain.verification.VerificationReport waitForReport(Long policyId) {
        // performVerification은 @Async라 폴링해서 끝날 때까지 기다린다. 300만 건 규모라 최대
        // 3분까지 기다려준다(§5.3 실측 기준 141초 안팎 — 여유를 크게 둠).
        long deadline = System.currentTimeMillis() + java.time.Duration.ofMinutes(3).toMillis();
        while (System.currentTimeMillis() < deadline) {
            var latest = verificationReportRepository
                    .findFirstByCouponPolicy_IdOrderByIdDesc(policyId).orElse(null);
            if (latest != null && latest.getStatus() != com.ureca.myureca.domain.verification.VerificationStatus.PENDING) {
                return latest;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("검증 대기 중 인터럽트됨. policyId=" + policyId, e);
            }
        }
        throw new IllegalStateException("검증이 3분 안에 끝나지 않았습니다. policyId=" + policyId);
    }

    private void sadd(String key, long from, long to) {
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[][] batch = new byte[2000][];
            int idx = 0;
            for (long i = from; i <= to; i++) {
                batch[idx++] = String.valueOf(i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (idx == batch.length) {
                    connection.setCommands().sAdd(keyBytes, batch);
                    idx = 0;
                }
            }
            if (idx > 0) {
                connection.setCommands().sAdd(keyBytes, java.util.Arrays.copyOf(batch, idx));
            }
            return null;
        });
    }

    private void zaddRange(String key, long from, long to, double score) {
        var ops = redisTemplate.opsForZSet();
        for (long i = from; i <= to; i++) {
            ops.add(key, String.valueOf(i), score);
        }
    }

    private void deleteRedisKeysFor(Long policyId) {
        redisTemplate.delete(RedisKeys.couponIssued(policyId));
        redisTemplate.delete(RedisKeys.couponReserved(policyId));
        redisTemplate.delete(RedisKeys.couponStock(policyId));
        redisTemplate.delete(RedisKeys.couponQueue(policyId));
        redisTemplate.delete(RedisKeys.couponPending(policyId));
    }

    private ScenarioResult result(Long policyId, String type, String description, int totalQuantity, long rows) {
        return new ScenarioResult(policyId, TITLE_PREFIX + type.toLowerCase().replace('_', '-'),
                type, description, totalQuantity, rows, null, null);
    }

    private String scenarioTypeOf(String title) {
        return title.replace(TITLE_PREFIX, "").toUpperCase().replace('-', '_');
    }

    private String scenarioDescriptionOf(String title) {
        return title; // status() 조회에서는 굳이 설명을 재구성하지 않고 제목만 보여준다.
    }
}
