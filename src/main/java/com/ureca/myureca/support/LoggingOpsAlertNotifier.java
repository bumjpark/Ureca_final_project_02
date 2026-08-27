package com.ureca.myureca.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link OpsAlertNotifier}의 기본(default) 구현. 실제 알림 채널이 붙기 전까지는
 * ERROR 레벨 로그로 남긴다 — {@code [OPS_ALERT]} 접두어로 검색/로그 기반 경보(ELK, CloudWatch
 * Alarm 등)에 바로 연결할 수 있게 한다.
 */
@Slf4j
@Component
public class LoggingOpsAlertNotifier implements OpsAlertNotifier {

    @Override
    public void alert(String subject, String detail) {
        log.error("[OPS_ALERT] {} - {}", subject, detail);
    }
}
