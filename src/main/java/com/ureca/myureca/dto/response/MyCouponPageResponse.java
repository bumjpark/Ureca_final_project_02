package com.ureca.myureca.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 내 쿠폰함 목록 응답.
 */
public record MyCouponPageResponse(
        MaskedUserResponse user,
        List<MyCouponResponse> coupons,
        PageInfo page
) {

    public record PageInfo(
            int number,
            int size,
            long totalElements,
            int totalPages,
            boolean hasNext
    ) {
        public static PageInfo from(Page<?> page) {
            return new PageInfo(
                    page.getNumber(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages(),
                    page.hasNext()
            );
        }
    }
}
