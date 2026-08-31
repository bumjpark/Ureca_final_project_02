package com.ureca.myureca.controller;

import com.ureca.myureca.dto.response.ScaleTestResponse;
import com.ureca.myureca.service.ScaleTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 300만 건 규모 정합성 검증 데모 — 관리자 화면 "딸깍" 버튼용. {@link ScaleTestService} 참고.
 *
 * <p>시딩·검증 둘 다 시간이 걸린다(시딩 수 분, 전체 검증 최대 3분) — 프론트는 이 호출들을
 * 동기로 기다리는 대신 로딩 상태를 보여주고, 끝나면 {@link #status()}로 최신 상태를 다시
 * 읽는 패턴을 권장한다(관리자 전용 도구라 별도 비동기 큐까지는 과함).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/scale-test")
public class ScaleTestController {

    private final ScaleTestService scaleTestService;

    @PostMapping("/seed")
    public ResponseEntity<ScaleTestResponse> seed() {
        return ResponseEntity.ok(scaleTestService.seed());
    }

    @PostMapping("/verify-all")
    public ResponseEntity<ScaleTestResponse> verifyAll() {
        return ResponseEntity.ok(scaleTestService.verifyAll());
    }

    @GetMapping("/status")
    public ResponseEntity<ScaleTestResponse> status() {
        return ResponseEntity.ok(scaleTestService.status());
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        scaleTestService.delete();
        return ResponseEntity.noContent().build();
    }
}
