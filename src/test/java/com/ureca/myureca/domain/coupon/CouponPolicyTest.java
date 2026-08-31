package com.ureca.myureca.domain.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * {@link CouponPolicy#effectiveStatusAt(LocalDateTime)} 검증.
 *
 * <p>저장된 {@code status}는 정책 생성 시 한 번 정해진 뒤 갱신되지 않는다({@code open()}을 부르는
 * 코드가 없다). 그래서 조회용 상태는 시각 기준으로 다시 계산해야 하고, 그 규칙이 여기 있다.
 */
class CouponPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 31, 15, 0);

    private CouponPolicy policy(LocalDateTime openAt, LocalDateTime closeAt, CouponPolicyStatus stored) {
        return new CouponPolicy("테스트", CouponType.FIXED, 1000, 100, openAt, closeAt, stored);
    }

    @Test
    void 오픈_시각_전이면_오픈전이다() {
        CouponPolicy policy = policy(NOW.plusMinutes(10), null, CouponPolicyStatus.BEFORE_OPEN);

        assertThat(policy.effectiveStatusAt(NOW)).isEqualTo(CouponPolicyStatus.BEFORE_OPEN);
    }

    @Test
    void 저장된_값이_BEFORE_OPEN이어도_오픈_시각이_지났으면_OPEN이다() {
        // 이 프로젝트에서 실제로 났던 버그 — 발급이 한창인 정책이 화면에 계속 "오픈전"으로 보였다.
        CouponPolicy policy = policy(NOW.minusMinutes(10), null, CouponPolicyStatus.BEFORE_OPEN);

        assertThat(policy.effectiveStatusAt(NOW)).isEqualTo(CouponPolicyStatus.OPEN);
    }

    @Test
    void 마감_기한이_지났으면_만료_배치가_아직_안_돌았어도_EXPIRED다() {
        CouponPolicy policy = policy(NOW.minusHours(2), NOW.minusMinutes(1), CouponPolicyStatus.BEFORE_OPEN);

        assertThat(policy.effectiveStatusAt(NOW)).isEqualTo(CouponPolicyStatus.EXPIRED);
    }

    @Test
    void 마감_기한이_없으면_오픈_이후로_계속_OPEN이다() {
        CouponPolicy policy = policy(NOW.minusDays(3), null, CouponPolicyStatus.OPEN);

        assertThat(policy.effectiveStatusAt(NOW)).isEqualTo(CouponPolicyStatus.OPEN);
    }

    /**
     * CLOSED(재고 소진)·EXPIRED(관리자 만료)·DELETED(소프트 삭제)는 시각만으로 되돌려 계산할 수 없는
     * 진짜 상태 전이다 — 시각 조건과 무관하게 저장된 값을 그대로 존중해야 한다.
     */
    @Test
    void 되돌릴_수_없는_상태는_시각과_무관하게_저장된_값을_그대로_쓴다() {
        LocalDateTime opened = NOW.minusHours(1);

        assertThat(policy(opened, null, CouponPolicyStatus.CLOSED).effectiveStatusAt(NOW))
                .isEqualTo(CouponPolicyStatus.CLOSED);
        assertThat(policy(opened, null, CouponPolicyStatus.EXPIRED).effectiveStatusAt(NOW))
                .isEqualTo(CouponPolicyStatus.EXPIRED);
        assertThat(policy(opened, null, CouponPolicyStatus.DELETED).effectiveStatusAt(NOW))
                .isEqualTo(CouponPolicyStatus.DELETED);
    }

    @Test
    void 소진_마감된_정책은_오픈_시각_전이어도_CLOSED로_남는다() {
        // 시각 규칙이 저장된 종료 상태를 덮어쓰지 않는지 확인(우선순위 검증).
        CouponPolicy policy = policy(NOW.plusMinutes(10), null, CouponPolicyStatus.CLOSED);

        assertThat(policy.effectiveStatusAt(NOW)).isEqualTo(CouponPolicyStatus.CLOSED);
    }
}
