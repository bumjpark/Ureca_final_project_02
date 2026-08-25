package com.ureca.myureca.exception;

/**
 * 접근 조건(전체 정책 중 재고가 남아있는 정책이 0개일 것) 위반 시 발생.
 * 라이브 발급 트래픽과 검증 배치가 DB 커넥션 풀·Redis를 나눠 쓰는 자원 경합을 막기 위한 가드.
 */
public class VerificationNotAllowedException extends RuntimeException {

    public VerificationNotAllowedException(String message) {
        super(message);
    }
}
