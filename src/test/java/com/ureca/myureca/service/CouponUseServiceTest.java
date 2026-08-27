package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponHistory;
import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.domain.coupon.HistoryPrevStatus;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.domain.user.User;
import com.ureca.myureca.dto.request.CouponUseRequest;
import com.ureca.myureca.dto.response.CouponUseResponse;
import com.ureca.myureca.exception.CouponIssueNotFoundException;
import com.ureca.myureca.exception.CouponNotOwnedException;
import com.ureca.myureca.exception.CouponStatusConflictException;
import com.ureca.myureca.repository.CouponHistoryRepository;
import com.ureca.myureca.repository.CouponIssueRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponUseServiceTest {

    private static final Long ISSUE_ID = 1L;
    private static final Long OWNER_ID = 10L;
    private static final String KEY = "use-20260821-abc123";
    private static final String RECEIPT_ID = "rcpt_abcdef0123456789";

    @Mock
    private CouponIssueRepository couponIssueRepository;
    @Mock
    private CouponHistoryRepository couponHistoryRepository;

    private CouponUseService couponUseService;
    private User owner;

    @BeforeEach
    void setUp() throws Exception {
        couponUseService = new CouponUseService(couponIssueRepository, couponHistoryRepository);
        owner = new User("owner@test.com", "홍길동");
        setId(owner, OWNER_ID);
    }

    @DisplayName("사용 처리: ISSUED → USED, usedAt 이 기록된다")
    @Test
    void 사용처리_성공() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        given_신규요청_이며_갱신성공(issue, IssueStatus.ISSUED, IssueStatus.USED);

        CouponUseResponse response = couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.USED, null));

        assertThat(response.prevStatus()).isEqualTo(HistoryPrevStatus.ISSUED);
        assertThat(response.status()).isEqualTo(IssueStatus.USED);
        assertThat(response.usedAt()).isNotNull();
        assertThat(response.replayed()).isFalse();
        assertThat(response.receiptId()).isEqualTo(RECEIPT_ID);
    }

    @DisplayName("사용 취소: USED → ISSUED, usedAt 은 null 로 되돌아간다")
    @Test
    void 사용취소_성공() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        issue.markUsed(LocalDateTime.now());
        given_신규요청_이며_갱신성공(issue, IssueStatus.USED, IssueStatus.ISSUED);

        CouponUseResponse response = couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.ISSUED, "주문 취소"));

        assertThat(response.status()).isEqualTo(IssueStatus.ISSUED);
        assertThat(response.usedAt()).isNull();
    }

    @DisplayName("만료 처리: ISSUED → EXPIRED")
    @Test
    void 만료처리_성공() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        given_신규요청_이며_갱신성공(issue, IssueStatus.ISSUED, IssueStatus.EXPIRED);

        CouponUseResponse response = couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.EXPIRED, null));

        assertThat(response.status()).isEqualTo(IssueStatus.EXPIRED);
        assertThat(response.usedAt()).isNull();
    }

    @DisplayName("같은 Idempotency-Key 재요청이면 재실행 없이 이전 결과를 재생한다")
    @Test
    void 멱등_재요청() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        issue.markUsed(LocalDateTime.now());
        CouponHistory history =
                new CouponHistory(issue, KEY, HistoryPrevStatus.ISSUED, IssueStatus.USED, null);
        when(couponHistoryRepository.findByRequestId(KEY)).thenReturn(Optional.of(history));

        CouponUseResponse response = couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.USED, null));

        assertThat(response.replayed()).isTrue();
        assertThat(response.status()).isEqualTo(IssueStatus.USED);
        verify(couponIssueRepository, never()).updateStatusIf(any(), any(), any(), any(), any());
    }

    @DisplayName("이미 사용된 쿠폰을 또 사용하면 409")
    @Test
    void 이미_사용된_쿠폰() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        issue.markUsed(LocalDateTime.now());
        when(couponHistoryRepository.findByRequestId(KEY)).thenReturn(Optional.empty());
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));

        assertThatThrownBy(() -> couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.USED, null)))
                .isInstanceOf(CouponStatusConflictException.class)
                .hasMessageContaining("이미 USED 상태");
    }

    @DisplayName("이미 취소된 쿠폰을 또 취소해도 같은 방식으로 안내한다")
    @Test
    void 이미_취소된_쿠폰() throws Exception {
        // 취소가 끝난 쿠폰은 ISSUED 다. 여기서 또 취소를 누르면 목표 상태와 현재 상태가 같아진다.
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        when(couponHistoryRepository.findByRequestId(KEY)).thenReturn(Optional.empty());
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));

        assertThatThrownBy(() -> couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.ISSUED, "주문 취소")))
                .isInstanceOf(CouponStatusConflictException.class)
                .hasMessageContaining("이미 ISSUED 상태");
    }

    @DisplayName("유효기간이 지난 쿠폰은 상태가 ISSUED 여도 사용할 수 없다")
    @Test
    void 유효기간_만료_쿠폰() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().minusDays(1));
        when(couponHistoryRepository.findByRequestId(KEY)).thenReturn(Optional.empty());
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));

        assertThatThrownBy(() -> couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.USED, null)))
                .isInstanceOf(CouponStatusConflictException.class)
                .hasMessageContaining("유효기간");
    }

    @DisplayName("close_at 이 null 이면 기한이 없으므로 사용할 수 있다")
    @Test
    void 기한없는_쿠폰() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, null);
        given_신규요청_이며_갱신성공(issue, IssueStatus.ISSUED, IssueStatus.USED);

        CouponUseResponse response = couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.USED, null));

        assertThat(response.status()).isEqualTo(IssueStatus.USED);
    }

    @DisplayName("남의 쿠폰은 사용할 수 없다")
    @Test
    void 타인_쿠폰() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        when(couponHistoryRepository.findByRequestId(KEY)).thenReturn(Optional.empty());
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));

        assertThatThrownBy(() -> couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(999L, IssueStatus.USED, null)))
                .isInstanceOf(CouponNotOwnedException.class);
    }

    @DisplayName("존재하지 않는 쿠폰은 404")
    @Test
    void 없는_쿠폰() {
        when(couponHistoryRepository.findByRequestId(KEY)).thenReturn(Optional.empty());
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.USED, null)))
                .isInstanceOf(CouponIssueNotFoundException.class);
    }

    @DisplayName("조건부 UPDATE 가 0건이면 다른 요청이 먼저 바꾼 것이므로 409")
    @Test
    void 동시요청에_밀림() throws Exception {
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        when(couponHistoryRepository.findByRequestId(KEY)).thenReturn(Optional.empty());
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(couponIssueRepository.updateStatusIf(
                eq(ISSUE_ID), eq(IssueStatus.ISSUED), eq(IssueStatus.USED), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.USED, null)))
                .isInstanceOf(CouponStatusConflictException.class)
                .hasMessageContaining("다른 요청");
    }

    @DisplayName("Idempotency-Key 가 없거나 64자를 넘으면 400")
    @Test
    void 잘못된_멱등키() {
        CouponUseRequest request = new CouponUseRequest(OWNER_ID, IssueStatus.USED, null);

        assertThatThrownBy(() -> couponUseService.changeStatus(ISSUE_ID, "  ", request))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> couponUseService.changeStatus(ISSUE_ID, "x".repeat(65), request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("사용 취소된 쿠폰은 새 멱등키로 다시 사용할 수 있다")
    @Test
    void 취소후_재사용() throws Exception {
        // 사용 취소를 마친 쿠폰 = ISSUED 이면서 usedAt 이 null 인 상태
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        String newKey = "use-20260826-second";
        when(couponHistoryRepository.findByRequestId(newKey)).thenReturn(Optional.empty());
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(couponIssueRepository.updateStatusIf(
                eq(ISSUE_ID), eq(IssueStatus.ISSUED), eq(IssueStatus.USED), any(), any()))
                .thenReturn(1);
        when(couponIssueRepository.getReferenceById(ISSUE_ID)).thenReturn(issue);

        CouponUseResponse response = couponUseService.changeStatus(
                ISSUE_ID, newKey, new CouponUseRequest(OWNER_ID, IssueStatus.USED, null));

        assertThat(response.status()).isEqualTo(IssueStatus.USED);
        assertThat(response.usedAt()).isNotNull();
    }

    @DisplayName("유효기간이 지났어도 이미 사용한 쿠폰의 사용 취소는 가능하다")
    @Test
    void 유효기간_지난_쿠폰_사용취소() throws Exception {
        // 유효기간 검사는 '사용' 에만 걸린다. 이미 쓴 것을 되돌리는 취소까지 막으면
        // 마감 직전에 사용한 주문을 영영 취소할 수 없게 된다.
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().minusDays(1));
        issue.markUsed(LocalDateTime.now());
        given_신규요청_이며_갱신성공(issue, IssueStatus.USED, IssueStatus.ISSUED);

        CouponUseResponse response = couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.ISSUED, "기간 지난 주문 취소"));

        assertThat(response.status()).isEqualTo(IssueStatus.ISSUED);
        assertThat(response.usedAt()).isNull();
    }

    @DisplayName("만료된 쿠폰은 사용 취소로 되살릴 수 없다")
    @Test
    void 만료쿠폰_되살리기_불가() throws Exception {
        CouponIssue issue = issue(IssueStatus.EXPIRED, LocalDateTime.now().plusDays(7));
        when(couponHistoryRepository.findByRequestId(KEY)).thenReturn(Optional.empty());
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));

        assertThatThrownBy(() -> couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.ISSUED, null)))
                .isInstanceOf(CouponStatusConflictException.class)
                .hasMessageContaining("EXPIRED");
    }

    @DisplayName("같은 멱등키로 사용 다음 취소를 보내면 조용히 무시되지 않고 409")
    @Test
    void 같은키로_다른_요청() throws Exception {
        // 사용과 취소가 한 엔드포인트를 공유하므로, 클라이언트가 "주문 1건 = 키 1개" 로
        // 키를 만들면 실제로 일어나는 시나리오다. 동시성이 아니라 순차 요청에서 재현된다.
        CouponIssue issue = issue(IssueStatus.ISSUED, LocalDateTime.now().plusDays(7));
        issue.markUsed(LocalDateTime.now());
        CouponHistory 사용_이력 =
                new CouponHistory(issue, KEY, HistoryPrevStatus.ISSUED, IssueStatus.USED, null);
        when(couponHistoryRepository.findByRequestId(KEY)).thenReturn(Optional.of(사용_이력));

        assertThatThrownBy(() -> couponUseService.changeStatus(
                ISSUE_ID, KEY, new CouponUseRequest(OWNER_ID, IssueStatus.ISSUED, "주문 취소")))
                .isInstanceOf(CouponStatusConflictException.class)
                .hasMessageContaining("Idempotency-Key");

        verify(couponIssueRepository, never()).updateStatusIf(any(), any(), any(), any(), any());
    }

    // --- helpers ---

    private void given_신규요청_이며_갱신성공(CouponIssue issue, IssueStatus from, IssueStatus to) {
        when(couponHistoryRepository.findByRequestId(KEY)).thenReturn(Optional.empty());
        when(couponIssueRepository.findDetailById(ISSUE_ID)).thenReturn(Optional.of(issue));
        when(couponIssueRepository.updateStatusIf(eq(ISSUE_ID), eq(from), eq(to), any(), any()))
                .thenReturn(1);
        when(couponIssueRepository.getReferenceById(ISSUE_ID)).thenReturn(issue);
    }

    private CouponIssue issue(IssueStatus status, LocalDateTime closeAt) throws Exception {
        CouponPolicy policy = new CouponPolicy(
                "테스트 쿠폰", CouponType.FIXED, 5000, 10000,
                LocalDateTime.now().minusDays(1), closeAt);
        setId(policy, 100L);

        CouponIssue issue = new CouponIssue(policy, owner, RECEIPT_ID, LocalDateTime.now());
        setId(issue, ISSUE_ID);
        setField(issue, "status", status);
        return issue;
    }

    private static void setId(Object target, Long id) throws Exception {
        setField(target, "id", id);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
