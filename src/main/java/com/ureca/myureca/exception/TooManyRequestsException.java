package com.ureca.myureca.exception;

/**
 * 동일 유저가 단시간에 대기열 진입을 연타(DDoS/매크로)했을 때 발생.
 *
 * <p>429 TOO_MANY_REQUESTS: 서버 리소스 및 Redis 대역폭 보호를 위한 인앱 컷.
 */
public class TooManyRequestsException extends RuntimeException {

    public TooManyRequestsException() {
        super("요청이 너무 빠릅니다. 잠시 후 다시 시도해 주세요.");
    }
}
