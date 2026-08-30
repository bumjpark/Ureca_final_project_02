-- =================================================================================
-- 정책별 Mock 알림톡 일괄 발송의 진행 상태 추적.
--
-- mock_notification_log(V8)는 "누구에게 뭘 보냈는지"만 개별 건으로 쌓인다 — 일괄 발송
-- 하나가 도중에 있는지(IN_PROGRESS) 다 끝났는지(COMPLETED), 대상자 중 몇 명이 끝났고
-- 그중 몇 명이 성공/실패했는지는 로그를 policyId+시간 범위로 다시 묶어 추정해야 했다.
-- "정책별로 발송이 진행됐는지, 일부만 됐는지"를 바로 보여주려면 그 집계 자체를
-- 발송 시작 시점에 만들어둔 행 하나로 들고 있는 게 맞다.
-- =================================================================================

USE coupon_db;

CREATE TABLE mock_notification_bulk_job (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '작업 고유 ID',
    coupon_policy_id  BIGINT       NOT NULL COMMENT '발송 대상 정책',
    template_id       VARCHAR(100) NOT NULL COMMENT '카카오 알림톡 템플릿 ID',
    message           VARCHAR(500) NOT NULL COMMENT '발송 메시지 본문',
    target_count      INT          NOT NULL COMMENT '대상자 수(시작 시점에 확정, 이후 안 바뀜)',
    sent_count        INT          NOT NULL DEFAULT 0 COMMENT '성공 발송 완료 수(진행 중 계속 증가)',
    failed_count      INT          NOT NULL DEFAULT 0 COMMENT '실패 발송 완료 수(진행 중 계속 증가)',
    status            VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS' COMMENT '작업 상태 (IN_PROGRESS/COMPLETED)',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '작업 시작 일시',
    completed_at      DATETIME     NULL     COMMENT '작업 완료 일시(IN_PROGRESS면 NULL)',

    KEY idx_mock_bulk_job_policy (coupon_policy_id, created_at),

    CONSTRAINT chk_mock_bulk_job_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='정책별 Mock 알림톡 일괄 발송 진행 상태(대상자 수 대비 성공/실패 완료 수)';
