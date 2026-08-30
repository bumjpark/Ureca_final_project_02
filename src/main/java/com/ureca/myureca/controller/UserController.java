package com.ureca.myureca.controller;

import com.ureca.myureca.dto.response.MaskedUserResponse;
import com.ureca.myureca.dto.response.PageResponse;
import com.ureca.myureca.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 로그인이 없는 시스템(FR-1)에서 시연용 userId를 이름/이메일로 찾거나 목록에서 고르기 위한
 * 조회 전용 API. 100만 건 규모 더미 유저 테이블이라, 검색어가 있으면 LIKE 검색을 타고
 * 없으면 PK 순서 기본 목록(findAll)을 탄다 — 어느 쪽도 WHERE 없는 전체 스캔은 하지 않는다.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;

    @GetMapping
    public PageResponse<MaskedUserResponse> search(
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        boolean blank = search == null || search.isBlank();
        Page<MaskedUserResponse> page = blank
                ? userRepository.findAll(pageable).map(MaskedUserResponse::from)
                : userRepository
                        .findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(search.trim(), search.trim(), pageable)
                        .map(MaskedUserResponse::from);
        return PageResponse.from(page);
    }
}
