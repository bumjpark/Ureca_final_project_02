package com.ureca.myureca.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * 5xx 원인 세분화(이슈: k6 부하테스트에서 메시지 문자열 매칭 없이는 실패 원인을 구분할 수 없던 문제) 검증.
 * Redis 장애와 DB 커넥션 풀 고갈이 더 이상 {@code handleUnexpected}의 뭉뚱그려진 500으로 빠지지 않고,
 * 재시도 가능함을 알리는 503 + 구분되는 {@code errorCode}로 응답하는지 확인한다.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void Redis_연결_실패는_503_REDIS_UNAVAILABLE로_응답한다() {
        ResponseEntity<ErrorResponse> response =
                handler.handleRedisUnavailable(new RedisConnectionFailureException("connection refused"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().errorCode()).isEqualTo("REDIS_UNAVAILABLE");
    }

    @Test
    void DB_커넥션_획득_실패는_503_DB_CONNECTION_UNAVAILABLE로_응답한다() {
        ResponseEntity<ErrorResponse> response = handler.handleConnectionUnavailable(
                new CannotCreateTransactionException("pool exhausted", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody().errorCode()).isEqualTo("DB_CONNECTION_UNAVAILABLE");
    }

    @Test
    void 분류되지_않은_예외는_500_INTERNAL_ERROR로_응답한다() {
        ResponseEntity<ErrorResponse> response = handler.handleUnexpected(new RuntimeException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().errorCode()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void 재고_소진은_400_OUT_OF_STOCK으로_응답한다() {
        ResponseEntity<ErrorResponse> response = handler.handleSoldOut(new CouponSoldOutException("품절"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void 중복_발급은_409_ALREADY_ISSUED로_응답한다() {
        ResponseEntity<ErrorResponse> response = handler.handleDuplicated(new CouponDuplicatedException("중복"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().errorCode()).isEqualTo("ALREADY_ISSUED");
    }

    /**
     * errorCode는 클라이언트와 부하테스트 스크립트가 문자열 매칭 없이 분기하는 계약이다.
     * 핸들러를 새로 추가하면서 코드를 안 채우면 그 응답만 조용히 {@code null}로 나가 예전처럼
     * 메시지 매칭으로 되돌아가게 되므로, "빠뜨림"을 여기서 잡는다.
     */
    @Test
    void 모든_예외_핸들러가_errorCode를_채운다() {
        List<Method> handlers = Arrays.stream(GlobalExceptionHandler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(ExceptionHandler.class))
                .toList();
        assertThat(handlers).isNotEmpty();

        List<String> missing = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (Method m : handlers) {
            ResponseEntity<ErrorResponse> response = invokeWithDummyArgs(m);
            if (response == null) {
                continue; // 인자를 만들 수 없는 핸들러는 위 개별 테스트가 담당한다
            }
            checked.add(m.getName());
            if (response.getBody() == null || response.getBody().errorCode() == null) {
                missing.add(m.getName());
            }
        }
        assertThat(missing)
                .as("errorCode를 채우지 않은 핸들러 — ErrorResponse.of(..., errorCode) 오버로드를 쓸 것")
                .isEmpty();
        // 인자를 못 만들어 전부 건너뛰면 위 단언이 공허하게 통과한다 — 실제로 상당수를
        // 호출했는지 함께 못박아, 리플렉션이 조용히 아무것도 검사하지 않는 상태를 막는다.
        assertThat(checked)
                .as("실제로 호출해서 검사한 핸들러 수가 너무 적다 — 이 테스트가 공허하게 통과하고 있다")
                .hasSizeGreaterThanOrEqualTo(handlers.size() / 2);
    }

    /** 핸들러가 받는 예외 타입을 리플렉션으로 만들어 호출한다. 못 만들면 null. */
    @SuppressWarnings("unchecked")
    private ResponseEntity<ErrorResponse> invokeWithDummyArgs(Method m) {
        Class<?>[] paramTypes = m.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Object arg = dummyException(paramTypes[i]);
            if (arg == null) {
                return null;
            }
            args[i] = arg;
        }
        try {
            return (ResponseEntity<ErrorResponse>) m.invoke(handler, args);
        } catch (Exception e) {
            return null;
        }
    }

    /** 예외 인스턴스를 만들 수 있는 첫 번째 생성자를 찾아 기본값으로 채운다. */
    private Object dummyException(Class<?> type) {
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            Object[] args = new Object[c.getParameterCount()];
            boolean ok = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> p = c.getParameterTypes()[i];
                if (p == String.class) {
                    args[i] = "dummy";
                } else if (p == Long.class || p == long.class) {
                    args[i] = 1L;
                } else if (p == Integer.class || p == int.class) {
                    args[i] = 1;
                } else if (p == List.class) {
                    args[i] = List.of();
                } else if (!p.isPrimitive()) {
                    args[i] = null;
                } else {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            try {
                c.setAccessible(true);
                return c.newInstance(args);
            } catch (Exception ignored) {
                // 다음 생성자로
            }
        }
        return null;
    }
}
