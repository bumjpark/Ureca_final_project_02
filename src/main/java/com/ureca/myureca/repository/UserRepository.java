package com.ureca.myureca.repository;

import com.ureca.myureca.domain.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 로그인이 없는 시스템(FR-1)에서 시연용 userId를 고르기 위한 검색.
     * 100만 건 규모 테이블이라 이름/이메일 LIKE 검색은 WHERE 조건 없는 전체 스캔이 되지 않도록
     * 검색어가 있을 때만 이 메서드를 탄다 — {@link com.ureca.myureca.service.CouponPolicyCacheService}의
     * negative caching과 같은 "빈 조건으로 대량 테이블을 때리지 않는다" 철학.
     * 검색어가 없는 기본 목록 조회는 {@link JpaRepository#findAll(Pageable)}을 그대로 쓴다 —
     * WHERE 없이 PK 순서로 LIMIT/OFFSET만 타므로(클러스터드 인덱스 스캔) 앞쪽 페이지는 저렴하다.
     */
    Page<User> findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(String name, String email, Pageable pageable);
}
