package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponHistory;
import com.ureca.myureca.domain.coupon.CouponIssue;
import com.ureca.myureca.domain.coupon.IssueStatus;
import com.ureca.myureca.dto.response.CouponHistoryResponse;
import com.ureca.myureca.exception.CouponIssueNotFoundException;
import com.ureca.myureca.repository.CouponHistoryRepository;
import com.ureca.myureca.repository.CouponIssueRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CouponHistoryServiceTest {

    private static final Long COUPON_ISSUE_ID = 1L;

    @Mock
    private CouponIssueRepository couponIssueRepository;

    @Mock
    private CouponHistoryRepository couponHistoryRepository;

    private CouponHistoryService couponHistoryService;

    @BeforeEach
    void setUp() {
        couponHistoryService = new CouponHistoryService(couponIssueRepository, couponHistoryRepository);
    }

    @Test
    void 쿠폰_발급건이_존재하지_않으면_예외가_발생한다() {
        when(couponIssueRepository.existsById(COUPON_ISSUE_ID)).thenReturn(false);

        assertThatThrownBy(() -> couponHistoryService.getCouponHistory(COUPON_ISSUE_ID))
                .isInstanceOf(CouponIssueNotFoundException.class);
    }

    @Test
    void 이력이_없는_경우_빈_리스트를_반환한다() {
        when(couponIssueRepository.existsById(COUPON_ISSUE_ID)).thenReturn(true);
        when(couponHistoryRepository.findByCouponIssueIdOrderByCreatedAtAsc(COUPON_ISSUE_ID))
                .thenReturn(List.of());

        List<CouponHistoryResponse> response = couponHistoryService.getCouponHistory(COUPON_ISSUE_ID);

        assertThat(response).isEmpty();
    }

    @Test
    void 쿠폰_이력을_시간순으로_조회한다() {
        when(couponIssueRepository.existsById(COUPON_ISSUE_ID)).thenReturn(true);
        
        CouponHistory history1 = new CouponHistory(null, "req-1", IssueStatus.ISSUED, IssueStatus.USED, null);
        ReflectionTestUtils.setField(history1, "createdAt", LocalDateTime.of(2023, 1, 1, 10, 0));
        
        CouponHistory history2 = new CouponHistory(null, "req-2", IssueStatus.ISSUED, IssueStatus.USED, null);
        ReflectionTestUtils.setField(history2, "createdAt", LocalDateTime.of(2023, 1, 1, 10, 5));

        when(couponHistoryRepository.findByCouponIssueIdOrderByCreatedAtAsc(COUPON_ISSUE_ID))
                .thenReturn(List.of(history1, history2));

        List<CouponHistoryResponse> response = couponHistoryService.getCouponHistory(COUPON_ISSUE_ID);

        assertThat(response).hasSize(2);
        assertThat(response.get(0).prevStatus()).isEqualTo(IssueStatus.ISSUED);
        assertThat(response.get(0).newStatus()).isEqualTo(IssueStatus.USED);
        assertThat(response.get(0).cancelReason()).isNull();
        assertThat(response.get(0).requestId()).isEqualTo("req-1");
        assertThat(response.get(0).createdAt()).isEqualTo(LocalDateTime.of(2023, 1, 1, 10, 0));

        assertThat(response.get(1).prevStatus()).isEqualTo(IssueStatus.ISSUED);
        assertThat(response.get(1).newStatus()).isEqualTo(IssueStatus.USED);
        assertThat(response.get(1).cancelReason()).isNull();
        assertThat(response.get(1).requestId()).isEqualTo("req-2");
        assertThat(response.get(1).createdAt()).isEqualTo(LocalDateTime.of(2023, 1, 1, 10, 5));
    }
}
