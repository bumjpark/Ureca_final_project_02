package com.ureca.myureca.controller;

import com.ureca.myureca.dto.response.HealthResponse;
import com.ureca.myureca.service.HealthCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인프라 헬스체크 엔드포인트. liveness / 의존성 체크(readiness)를 하나의 경로에서
 * 쿼리 파라미터로 구분한다.
 *
 * <ul>
 *   <li><b>GET /api/health</b> — liveness. DB/Redis/Kafka를 건드리지 않고
 *       프로세스가 응답 가능한지만 즉시 200으로 알려준다. 로드밸런서/오케스트레이터가
 *       짧은 주기로 반복 호출해도(부하테스트 트래픽이 몰리는 순간에도) 비용이 거의 없다.</li>
 *   <li><b>GET /api/health?deep=true</b> — readiness. MySQL 전용 커넥션 풀 확인,
 *       Redis PING, Kafka 브로커 조회를 병렬로 수행한다. k6 시작 전 사전 점검,
 *       Redis 재구성 전 상태 확인처럼 "의도적으로, 가끔" 호출하는 용도다.
 *       하나라도 DOWN이면 503을 반환한다.</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class HealthController {

    private final HealthCheckService healthCheckService;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health(
            @RequestParam(name = "deep", defaultValue = "false") boolean deep) {
        if (!deep) {
            return ResponseEntity.ok(HealthResponse.liveness());
        }

        HealthResponse response = healthCheckService.check();
        HttpStatus httpStatus = response.isUp() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(httpStatus).body(response);
    }
}
