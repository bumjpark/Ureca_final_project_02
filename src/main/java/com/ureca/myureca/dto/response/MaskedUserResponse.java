package com.ureca.myureca.dto.response;

import com.ureca.myureca.domain.user.User;
import com.ureca.myureca.util.MaskingUtils;

/**
 * 응답에 노출되는 사용자 정보. 생성 경로가 {from(User)} 하나뿐이라
 * 마스킹을 거치지 않은 원본이 응답에 실릴 수 없다.
 */
public record MaskedUserResponse(
        Long userId,
        String name,
        String email
) {

    public static MaskedUserResponse from(User user) {
        return new MaskedUserResponse(
                user.getId(),
                MaskingUtils.maskName(user.getName()),
                MaskingUtils.maskEmail(user.getEmail())
        );
    }
}
