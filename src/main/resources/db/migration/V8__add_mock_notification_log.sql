-- =================================================================================
-- Mock 카카오 알림톡 발송 이력 영속화.
--
-- MockNotificationController는 지금까지 발송 자체(지연 시뮬레이션 + 성공/실패 판정)만
-- 하고 어디에도 기록을 남기지 않았다 — 프론트에서 "방금 뭘 보냈는지" 확인할 방법이
-- 새로고침 전까지의 세션 로컬 상태뿐이었다. 정책별 일괄 발송(관리자가 특정 정책 수신자
-- 전원에게 보내는 기능)까지 추가되면서, 그 결과를 나중에도 조회할 수 있는 저장소가 필요해졌다.
--
-- coupon_policy_id는 nullable — 단건 발송(관리자가 임의 userId 하나에 보내는 경우)은
-- 특정 정책과 무관할 수 있고, 일괄 발송(정책 수신자 전원 대상)일 때만 채워진다.
-- =================================================================================

USE coupon_db;

CREATE TABLE mock_notification_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '로그 고유 ID',
    user_id           BIGINT       NOT NULL COMMENT '수신 유저 ID',
    coupon_policy_id  BIGINT       NULL     COMMENT '일괄 발송의 대상 정책(단건 발송이면 NULL)',
    template_id       VARCHAR(100) NOT NULL COMMENT '카카오 알림톡 템플릿 ID',
    message           VARCHAR(500) NOT NULL COMMENT '발송 메시지 본문',
    status            VARCHAR(20)  NOT NULL COMMENT '발송 결과 (SENT/FAILED)',
    message_id        VARCHAR(100) NOT NULL COMMENT 'Mock 발송 응답의 messageId(추적용)',
    fail_reason       VARCHAR(255) NULL     COMMENT '실패 사유(현재는 강제 실패/확률 실패만 있어 고정 문구)',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '발송 일시',

    KEY idx_mock_notification_policy (coupon_policy_id, created_at),
    KEY idx_mock_notification_user (user_id),

    CONSTRAINT chk_mock_notification_status CHECK (status IN ('SENT', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Mock 카카오 알림톡 발송 이력 (단건/정책별 일괄 발송 공통)';
