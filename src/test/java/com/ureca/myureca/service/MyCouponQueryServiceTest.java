package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.domain.user.User;
import com.ureca.myureca.dto.response.CouponDetailResponse;
import com.ureca.myureca.dto.response.MyCouponPageResponse;
import com.ureca.myureca.dto.response.MyCouponResponse;
import com.ureca.myureca.exception.CouponIssueNotFoundException;
import com.ureca.myureca.exception.CouponNotOwnedException;
import com.ureca.myureca.exception.UserNotFoundException;
import com.ureca.myureca.repository.CouponIssueRepository;
import com.ureca.myureca.repository.ReconciliationLogRepository;
import com.ureca.myureca.repository.UserRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * 쿠폰 단건 상태 조회(상세 화면) 검증.
 *
 * 만료 판정은 CouponIssue 에 있고 목록·상세·사용처리가 공유하므로,
 * 여기서 고정해두면 세 경로가 어긋나는 것을 막을 수 있다.
 */
@ExtendWith(MockitoExtension.class)
class MyCouponQueryServiceTest {

    private static final Long ISSUE_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final Long POLICY_ID = 100L;
    private static final String RECEIPT_ID = "rcpt_abcdef0123456789";

    @Mock
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ReconciliationLogRepository reconciliationLogRepository;

    private MyCouponQueryService myCouponQueryService;
    private User owner;

    @BeforeEach
    void setUp() throws Exception {
        myCouponQueryService =
                new MyCouponQueryService(couponIssueRepository, userRepository, reconciliationLogRepository);
        owner = new User("pcy9849@gmail.com", "홍길동");
        setField(owner, "id", OWNER_ID);
    }

    @DisplayName("상세 조회: 쿠폰 정보와 마스킹된 소유자 정보를 함께 준다")
    @Test
    void 상세조회_성공() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));

        CouponDetailResponse response = myCouponQueryService.getCouponDetail(ISSUE_ID, OWNER_ID);

        assertThat(response.couponIssueId()).isEqualTo(ISSUE_ID);
        assertThat(response.receiptId()).isEqualTo(RECEIPT_ID);
        assertThat(response.couponPolicyId()).isEqualTo(POLICY_ID);
        assertThat(response.discountLabel()).isEqualTo("5,000원 할인");
        assertThat(response.status()).isEqualTo(IssueStatus.ISSUED);
        assertThat(response.displayStatus()).isEqualTo(IssueStatus.ISSUED);
        assertThat(response.usable()).isTrue();
        assertThat(response.openAt()).isNotNull();
    }

    @DisplayName("소유자 정보는 마스킹해서 내려간다 (FR-2)")
    @Test
    void 상세조회_마스킹() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));

        CouponDetailResponse response = myCouponQueryService.getCouponDetail(ISSUE_ID, OWNER_ID);

        assertThat(response.user().userId()).isEqualTo(OWNER_ID);
        assertThat(response.user().name()).isEqualTo("홍*동");
        assertThat(response.user().email()).isEqualTo("pc*****@gmail.com");
    }

    @DisplayName("존재하지 않는 쿠폰은 404")
    @Test
    void 없는_쿠폰() {
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> myCouponQueryService.getCouponDetail(ISSUE_ID, OWNER_ID))
                .isInstanceOf(CouponIssueNotFoundException.class);
    }

    @DisplayName("남의 쿠폰은 조회할 수 없다")
    @Test
    void 타인_쿠폰() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));

        assertThatThrownBy(() -> myCouponQueryService.getCouponDetail(ISSUE_ID, 999L))
                .isInstanceOf(CouponNotOwnedException.class);
    }

    @DisplayName("유효기간이 지나면 DB 가 ISSUED 여도 displayStatus 는 EXPIRED 이고 사용 불가다")
    @Test
    void 만료_보정() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().minusDays(1));
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));

        CouponDetailResponse response = myCouponQueryService.getCouponDetail(ISSUE_ID, OWNER_ID);

        assertThat(response.status()).isEqualTo(IssueStatus.ISSUED);
        assertThat(response.displayStatus()).isEqualTo(IssueStatus.EXPIRED);
        assertThat(response.usable()).isFalse();
    }

    @DisplayName("close_at 이 null 이면 기한이 없으므로 만료되지 않는다")
    @Test
    void 기한없는_쿠폰() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, null);
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));

        CouponDetailResponse response = myCouponQueryService.getCouponDetail(ISSUE_ID, OWNER_ID);

        assertThat(response.displayStatus()).isEqualTo(IssueStatus.ISSUED);
        assertThat(response.usable()).isTrue();
        assertThat(response.expiresAt()).isNull();
    }

    @DisplayName("이미 사용한 쿠폰은 기간이 남아 있어도 사용 불가다")
    @Test
    void 사용된_쿠폰() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        issue.markUsed(LocalDateTime.now());
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));

        CouponDetailResponse response = myCouponQueryService.getCouponDetail(ISSUE_ID, OWNER_ID);

        assertThat(response.displayStatus()).isEqualTo(IssueStatus.USED);
        assertThat(response.usable()).isFalse();
        assertThat(response.usedAt()).isNotNull();
    }

    // ---------- 발급 접수(receiptId) 상태 조회 ----------

    @DisplayName("DB에 반영되면 ISSUED와 상세를 함께 준다")
    @Test
    void 접수상태_ISSUED() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        when(couponIssueRepository.findByReceiptId(RECEIPT_ID)).thenReturn(Optional.of(issue));

        var response = myCouponQueryService.getIssueStatusByReceiptId(RECEIPT_ID);

        assertThat(response.status())
                .isEqualTo(com.ureca.myureca.dto.response.CouponIssueStatusResponse.Status.ISSUED);
        assertThat(response.coupon()).isNotNull();
        assertThat(response.coupon().receiptId()).isEqualTo(RECEIPT_ID);
    }

    @DisplayName("DB에도 재처리 로그에도 없으면 PENDING(정상적인 비동기 처리 중일 수 있음)이다")
    @Test
    void 접수상태_PENDING_아직_아무_흔적도_없음() {
        when(couponIssueRepository.findByReceiptId(RECEIPT_ID)).thenReturn(Optional.empty());
        when(reconciliationLogRepository.findByEventKey(RECEIPT_ID)).thenReturn(Optional.empty());

        var response = myCouponQueryService.getIssueStatusByReceiptId(RECEIPT_ID);

        assertThat(response.status())
                .isEqualTo(com.ureca.myureca.dto.response.CouponIssueStatusResponse.Status.PENDING);
        assertThat(response.coupon()).isNull();
    }

    @DisplayName("재처리 로그가 PENDING/FAILED로 남아있으면 FAILED(재처리 중임을 알림)다")
    @Test
    void 접수상태_FAILED_재처리_로그가_미해결() {
        com.ureca.myureca.domain.reconciliation.ReconciliationLog log =
                new com.ureca.myureca.domain.reconciliation.ReconciliationLog(
                        com.ureca.myureca.domain.reconciliation.ReconciliationType.EVENT_REPUBLISH,
                        RECEIPT_ID, null, "coupon-issued-events", "{}", null);
        log.recordOriginalFailure("Kafka 발행 실패");
        when(couponIssueRepository.findByReceiptId(RECEIPT_ID)).thenReturn(Optional.empty());
        when(reconciliationLogRepository.findByEventKey(RECEIPT_ID)).thenReturn(Optional.of(log));

        var response = myCouponQueryService.getIssueStatusByReceiptId(RECEIPT_ID);

        assertThat(response.status())
                .isEqualTo(com.ureca.myureca.dto.response.CouponIssueStatusResponse.Status.FAILED);
        assertThat(response.note()).contains("Kafka 발행 실패");
    }

    @DisplayName("재처리 로그가 이미 SUCCESS면 Consumer가 곧 따라잡을 것이므로 PENDING과 동일하게 본다")
    @Test
    void 접수상태_재처리_성공건은_PENDING으로_본다() {
        com.ureca.myureca.domain.reconciliation.ReconciliationLog log =
                new com.ureca.myureca.domain.reconciliation.ReconciliationLog(
                        com.ureca.myureca.domain.reconciliation.ReconciliationType.EVENT_REPUBLISH,
                        RECEIPT_ID, null, "coupon-issued-events", "{}", null);
        log.markSuccess();
        when(couponIssueRepository.findByReceiptId(RECEIPT_ID)).thenReturn(Optional.empty());
        when(reconciliationLogRepository.findByEventKey(RECEIPT_ID)).thenReturn(Optional.of(log));

        var response = myCouponQueryService.getIssueStatusByReceiptId(RECEIPT_ID);

        assertThat(response.status())
                .isEqualTo(com.ureca.myureca.dto.response.CouponIssueStatusResponse.Status.PENDING);
    }

    // ---------- 내 쿠폰함 목록 ----------

    @DisplayName("목록: couponIssueId 를 내려준다 — 없으면 상세·이력·사용처리로 넘어갈 수 없다")
    @Test
    void 목록조회_couponIssueId_포함() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        given_쿠폰함에_한_장(issue);

        MyCouponPageResponse response = myCouponQueryService.getMyCoupons(
                OWNER_ID, null, null, PageRequest.of(0, 20));

        MyCouponResponse coupon = response.coupons().get(0);
        assertThat(coupon.couponIssueId()).isEqualTo(ISSUE_ID);
        assertThat(coupon.receiptId()).isEqualTo(RECEIPT_ID);
        assertThat(coupon.discountLabel()).isEqualTo("5,000원 할인");
        assertThat(response.user().name()).isEqualTo("홍*동");
        assertThat(response.page().totalElements()).isEqualTo(1);
    }

    @DisplayName("목록도 상세와 똑같이 만료를 보정한다 — 두 화면이 어긋나면 안 된다")
    @Test
    void 목록조회_만료_보정() throws Exception {
        CouponIssue expired = issue(IssueStatus.ISSUED, LocalDateTime.now().minusDays(1));
        given_쿠폰함에_한_장(expired);

        MyCouponResponse coupon = myCouponQueryService
                .getMyCoupons(OWNER_ID, null, null, PageRequest.of(0, 20))
                .coupons().get(0);

        assertThat(coupon.status()).isEqualTo(IssueStatus.ISSUED);
        assertThat(coupon.displayStatus()).isEqualTo(IssueStatus.EXPIRED);
        assertThat(coupon.usable()).isFalse();
    }

    @DisplayName("존재하지 않는 userId 는 빈 쿠폰함이 아니라 404 다")
    @Test
    void 목록조회_없는_유저() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> myCouponQueryService.getMyCoupons(
                999L, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(UserNotFoundException.class);
    }

    @DisplayName("캠페인 필터를 주면 그 조건으로 조회한다 (1인 1매 검증 경로)")
    @Test
    void 목록조회_캠페인_필터() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(couponIssueRepository.findByUserIdAndCouponPolicyId(
                eq(OWNER_ID), eq(POLICY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(issue)));

        MyCouponPageResponse response = myCouponQueryService.getMyCoupons(
                OWNER_ID, null, POLICY_ID, PageRequest.of(0, 20));

        // 이 값이 0 또는 1 이어야 1인 1매(FR-8)가 지켜진 것이다
        assertThat(response.page().totalElements()).isEqualTo(1);
        verify(couponIssueRepository)
                .findByUserIdAndCouponPolicyId(eq(OWNER_ID), eq(POLICY_ID), any(Pageable.class));
    }

    // --- helpers ---

    private void given_쿠폰함에_한_장(CouponIssue issue) {
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(couponIssueRepository.findByUserId(eq(OWNER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(issue)));
    }

    private CouponIssue issue(IssueStatus status, LocalDateTime closeAt) throws Exception {
        CouponPolicy policy = new CouponPolicy(
                "신규가입 5000원 할인", CouponType.FIXED, 5000, 10000,
                LocalDateTime.now().minusDays(1), closeAt);
        setField(policy, "id", POLICY_ID);

        CouponIssue issue = new CouponIssue(policy, owner, RECEIPT_ID, LocalDateTime.now());
        setField(issue, "id", ISSUE_ID);
        setField(issue, "status", status);
        return issue;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
