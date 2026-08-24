package com.ureca.myureca.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ureca.myureca.domain.coupon.CouponPolicy;
import com.ureca.myureca.domain.coupon.CouponType;
import com.ureca.myureca.dto.request.CouponPolicyCreateRequest;
import com.ureca.myureca.dto.request.CouponPolicyUpdateRequest;
import com.ureca.myureca.dto.response.CouponPolicyResponse;
import com.ureca.myureca.exception.CouponPolicyNotFoundException;
import com.ureca.myureca.exception.InvalidCouponPolicyException;
import com.ureca.myureca.repository.CouponPolicyRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CouponPolicyServiceTest {

        @Mock
        private CouponPolicyRepository couponPolicyRepository;

        private CouponPolicyService couponPolicyService;

        @BeforeEach
        void setUp() {
                couponPolicyService = new CouponPolicyService(couponPolicyRepository);
        }

        @Test
        void 정책_생성에_성공하면_저장된_값을_응답으로_반환한다() {
                LocalDateTime openAt = LocalDateTime.now().plusDays(1);
                CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                                "여름 휴가 쿠폰", CouponType.FIXED, 5000, 10000, openAt, null);
                CouponPolicy saved = new CouponPolicy(
                                request.title(), request.couponType(), request.discountValue(),
                                request.totalQuantity(), request.openAt(), request.closeAt());
                when(couponPolicyRepository.save(any(CouponPolicy.class))).thenReturn(saved);

                CouponPolicyResponse response = couponPolicyService.createCouponPolicy(request);

                assertThat(response.title()).isEqualTo("여름 휴가 쿠폰");
                assertThat(response.couponType()).isEqualTo(CouponType.FIXED);
                assertThat(response.discountValue()).isEqualTo(5000);
                assertThat(response.totalQuantity()).isEqualTo(10000);
                assertThat(response.issuedQuantity()).isEqualTo(0);
                verify(couponPolicyRepository).save(any(CouponPolicy.class));
        }

        @Test
        void closeAt이_openAt보다_이전이면_예외가_발생한다() {
                LocalDateTime openAt = LocalDateTime.now().plusDays(2);
                LocalDateTime closeAt = LocalDateTime.now().plusDays(1);
                CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                                "잘못된 기간 쿠폰", CouponType.FIXED, 1000, 100, openAt, closeAt);

                assertThatThrownBy(() -> couponPolicyService.createCouponPolicy(request))
                                .isInstanceOf(InvalidCouponPolicyException.class)
                                .hasMessageContaining("closeAt");
        }

        @Test
        void RATE_타입에서_할인율이_100을_초과하면_예외가_발생한다() {
                LocalDateTime openAt = LocalDateTime.now().plusDays(1);
                CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                                "잘못된 할인율 쿠폰", CouponType.RATE, 150, 100, openAt, null);

                assertThatThrownBy(() -> couponPolicyService.createCouponPolicy(request))
                                .isInstanceOf(InvalidCouponPolicyException.class)
                                .hasMessageContaining("RATE");
        }

        @Test
        void closeAt이_openAt과_정확히_같으면_예외가_발생한다() {
                LocalDateTime openAt = LocalDateTime.now().plusDays(1);
                CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                                "경계값 쿠폰", CouponType.FIXED, 1000, 100, openAt, openAt);

                assertThatThrownBy(() -> couponPolicyService.createCouponPolicy(request))
                                .isInstanceOf(InvalidCouponPolicyException.class)
                                .hasMessageContaining("closeAt");
        }

        @Test
        void closeAt이_openAt보다_1초라도_이후면_성공한다() {
                LocalDateTime openAt = LocalDateTime.now().plusDays(1);
                LocalDateTime closeAt = openAt.plusSeconds(1);
                CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                                "경계값 쿠폰", CouponType.FIXED, 1000, 100, openAt, closeAt);
                CouponPolicy saved = new CouponPolicy(
                                request.title(), request.couponType(), request.discountValue(),
                                request.totalQuantity(), request.openAt(), request.closeAt());
                when(couponPolicyRepository.save(any(CouponPolicy.class))).thenReturn(saved);

                CouponPolicyResponse response = couponPolicyService.createCouponPolicy(request);

                assertThat(response.closeAt()).isEqualTo(closeAt);
        }

        @Test
        void RATE_할인율_최소값_1은_통과한다() {
                LocalDateTime openAt = LocalDateTime.now().plusDays(1);
                CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                                "경계값 쿠폰", CouponType.RATE, 1, 100, openAt, null);
                CouponPolicy saved = new CouponPolicy(
                                request.title(), request.couponType(), request.discountValue(),
                                request.totalQuantity(), request.openAt(), request.closeAt());
                when(couponPolicyRepository.save(any(CouponPolicy.class))).thenReturn(saved);

                CouponPolicyResponse response = couponPolicyService.createCouponPolicy(request);

                assertThat(response.discountValue()).isEqualTo(1);
        }

        @Test
        void RATE_할인율_최대값_100은_통과한다() {
                LocalDateTime openAt = LocalDateTime.now().plusDays(1);
                CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                                "경계값 쿠폰", CouponType.RATE, 100, 100, openAt, null);
                CouponPolicy saved = new CouponPolicy(
                                request.title(), request.couponType(), request.discountValue(),
                                request.totalQuantity(), request.openAt(), request.closeAt());
                when(couponPolicyRepository.save(any(CouponPolicy.class))).thenReturn(saved);

                CouponPolicyResponse response = couponPolicyService.createCouponPolicy(request);

                assertThat(response.discountValue()).isEqualTo(100);
        }

        @Test
        void RATE_할인율_101은_최대값_초과라_예외가_발생한다() {
                LocalDateTime openAt = LocalDateTime.now().plusDays(1);
                CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                                "경계값 쿠폰", CouponType.RATE, 101, 100, openAt, null);

                assertThatThrownBy(() -> couponPolicyService.createCouponPolicy(request))
                                .isInstanceOf(InvalidCouponPolicyException.class)
                                .hasMessageContaining("RATE");
        }

        @Test
        void FIXED_타입은_할인율_범위_제한을_받지_않는다() {
                LocalDateTime openAt = LocalDateTime.now().plusDays(1);
                CouponPolicyCreateRequest request = new CouponPolicyCreateRequest(
                                "고액 정액 쿠폰", CouponType.FIXED, 999_999, 100, openAt, null);
                CouponPolicy saved = new CouponPolicy(
                                request.title(), request.couponType(), request.discountValue(),
                                request.totalQuantity(), request.openAt(), request.closeAt());
                when(couponPolicyRepository.save(any(CouponPolicy.class))).thenReturn(saved);

                CouponPolicyResponse response = couponPolicyService.createCouponPolicy(request);

                assertThat(response.discountValue()).isEqualTo(999_999);
        }

        @Test
        void 오픈_전_정책_수정에_성공하면_수정된_정보를_반환한다() {
                Long policyId = 1L;
                LocalDateTime originalOpenAt = LocalDateTime.now().plusDays(1);
                CouponPolicy policy = new CouponPolicy(
                                "기존 쿠폰", CouponType.FIXED, 1000, 100, originalOpenAt, null);
                when(couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId))
                                .thenReturn(Optional.of(policy));

                LocalDateTime newOpenAt = LocalDateTime.now().plusDays(2);
                CouponPolicyUpdateRequest request = new CouponPolicyUpdateRequest(
                                "수정된 쿠폰", CouponType.RATE, 20, 200, newOpenAt, null);

                CouponPolicyResponse response = couponPolicyService.updateCouponPolicy(policyId, request);

                assertThat(response.title()).isEqualTo("수정된 쿠폰");
                assertThat(response.couponType()).isEqualTo(CouponType.RATE);
                assertThat(response.discountValue()).isEqualTo(20);
                assertThat(response.totalQuantity()).isEqualTo(200);
                assertThat(response.openAt()).isEqualTo(newOpenAt);
        }

        @Test
        void 존재하지_않는_정책_수정_시_CouponPolicyNotFoundException이_발생한다() {
                Long nonExistentId = 999L;
                when(couponPolicyRepository.findByIdAndDeletedAtIsNull(nonExistentId))
                                .thenReturn(Optional.empty());

                CouponPolicyUpdateRequest request = new CouponPolicyUpdateRequest(
                                "수정 쿠폰", CouponType.FIXED, 1000, 100, LocalDateTime.now().plusDays(1), null);

                assertThatThrownBy(() -> couponPolicyService.updateCouponPolicy(nonExistentId, request))
                                .isInstanceOf(CouponPolicyNotFoundException.class);
        }

        @Test
        void 이미_오픈된_정책을_수정하려_하면_InvalidCouponPolicyException이_발생한다() {
                Long policyId = 1L;
                LocalDateTime pastOpenAt = LocalDateTime.now().minusHours(1);
                CouponPolicy policy = new CouponPolicy(
                                "이미 오픈된 쿠폰", CouponType.FIXED, 1000, 100, pastOpenAt, null);
                when(couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId))
                                .thenReturn(Optional.of(policy));

                CouponPolicyUpdateRequest request = new CouponPolicyUpdateRequest(
                                "수정 시도 쿠폰", CouponType.FIXED, 2000, 100, LocalDateTime.now().plusDays(1), null);

                assertThatThrownBy(() -> couponPolicyService.updateCouponPolicy(policyId, request))
                                .isInstanceOf(InvalidCouponPolicyException.class)
                                .hasMessageContaining("오픈 시각 이후");
        }

        @Test
        void 수정_시_closeAt이_openAt보다_이전이면_예외가_발생한다() {
                Long policyId = 1L;
                LocalDateTime originalOpenAt = LocalDateTime.now().plusDays(1);
                CouponPolicy policy = new CouponPolicy(
                                "기존 쿠폰", CouponType.FIXED, 1000, 100, originalOpenAt, null);
                when(couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId))
                                .thenReturn(Optional.of(policy));

                LocalDateTime newOpenAt = LocalDateTime.now().plusDays(3);
                LocalDateTime newCloseAt = LocalDateTime.now().plusDays(2);
                CouponPolicyUpdateRequest request = new CouponPolicyUpdateRequest(
                                "기간 오류 쿠폰", CouponType.FIXED, 1000, 100, newOpenAt, newCloseAt);

                assertThatThrownBy(() -> couponPolicyService.updateCouponPolicy(policyId, request))
                                .isInstanceOf(InvalidCouponPolicyException.class)
                                .hasMessageContaining("closeAt");
        }

        @Test
        void 수정_시_RATE_할인율이_100을_초과하면_예외가_발생한다() {
                Long policyId = 1L;
                LocalDateTime originalOpenAt = LocalDateTime.now().plusDays(1);
                CouponPolicy policy = new CouponPolicy(
                                "기존 쿠폰", CouponType.FIXED, 1000, 100, originalOpenAt, null);
                when(couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId))
                                .thenReturn(Optional.of(policy));

                CouponPolicyUpdateRequest request = new CouponPolicyUpdateRequest(
                                "할인율 초과 쿠폰", CouponType.RATE, 101, 100, LocalDateTime.now().plusDays(2), null);

                assertThatThrownBy(() -> couponPolicyService.updateCouponPolicy(policyId, request))
                                .isInstanceOf(InvalidCouponPolicyException.class)
                                .hasMessageContaining("RATE");
        }

        @Test
        void 정책_삭제에_성공하면_소프트_삭제된다() {
                Long policyId = 1L;
                CouponPolicy policy = new CouponPolicy(
                                "삭제할 쿠폰", CouponType.FIXED, 1000, 100, LocalDateTime.now().plusDays(1), null);
                when(couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId))
                                .thenReturn(Optional.of(policy));

                couponPolicyService.deleteCouponPolicy(policyId);

                assertThat(policy.isDeleted()).isTrue();
                assertThat(policy.getDeletedAt()).isNotNull();
        }

        @Test
        void 이미_오픈된_정책을_삭제하려_하면_InvalidCouponPolicyException이_발생한다() {
                Long policyId = 1L;
                LocalDateTime pastOpenAt = LocalDateTime.now().minusHours(1);
                CouponPolicy policy = new CouponPolicy(
                                "이미 오픈된 쿠폰", CouponType.FIXED, 1000, 100, pastOpenAt, null);
                when(couponPolicyRepository.findByIdAndDeletedAtIsNull(policyId))
                                .thenReturn(Optional.of(policy));

                assertThatThrownBy(() -> couponPolicyService.deleteCouponPolicy(policyId))
                                .isInstanceOf(InvalidCouponPolicyException.class)
                                .hasMessageContaining("오픈 시각 이후");
        }

        @Test
        void 존재하지_않거나_이미_삭제된_정책_삭제시_CouponPolicyNotFoundException이_발생한다() {
                Long nonExistentId = 999L;
                when(couponPolicyRepository.findByIdAndDeletedAtIsNull(nonExistentId))
                                .thenReturn(Optional.empty());

                assertThatThrownBy(() -> couponPolicyService.deleteCouponPolicy(nonExistentId))
                                .isInstanceOf(CouponPolicyNotFoundException.class);
        }
}
