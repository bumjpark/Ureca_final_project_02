package com.ureca.myureca.support;

/**
 * 인프라 이상 상황(장애 감지/복구)을 운영자에게 알리는 채널의 추상화.
 */
public interface OpsAlertNotifier {

    void alert(String subject, String detail);
}
