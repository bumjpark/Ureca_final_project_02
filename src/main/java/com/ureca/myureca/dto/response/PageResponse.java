package com.ureca.myureca.dto.response;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * 목록 조회 공통 페이지 응답. Spring Data {@link Page}의 내부 구현 필드
 * (pageable, sort, empty, first, last, numberOfElements 등)를 그대로 노출하지 않고
 * 프론트에서 페이지 번호 UI를 만드는 데 필요한 값만 담는다.
 *
 * <p>{@code page}는 Spring Data와 동일하게 0부터 시작한다.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }
}
