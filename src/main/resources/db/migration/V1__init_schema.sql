-- =================================================================================
-- 대규모 선착순 쿠폰 발급 시스템 최종 DB 스키마 (MySQL 8.0.16 이상 권장)
-- 
-- 1. 전제 아키텍처: Redis Lua가 발급 정합성을 원자적 보장, Kafka로 DB 비동기 Decouple
-- 2. 상태 정책: 취소는 '사용 취소'만 존재 (USED ➔ ISSUED 복귀, 상태는 ISSUED/USED/EXPIRED 3종)
-- 3. 멱등성: coupon_history.request_id에 UNIQUE를 걸어 요청별 중복 처리 완벽 차단
-- 4. 추적성: 202 ACCEPTED 접수증(receipt_id)을 coupon_issue에 함께 기록
-- 5. 자가 치유: Kafka 전송 실패/상태 불일치 건을 reconciliation_log에 기록 후 복구
-- =================================================================================

CREATE DATABASE IF NOT EXISTS coupon_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE coupon_db;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS verification_report;
DROP TABLE IF EXISTS reconciliation_log;
DROP TABLE IF EXISTS coupon_history;
DROP TABLE IF EXISTS coupon_issue;
DROP TABLE IF EXISTS coupon_policy;
DROP TABLE IF EXISTS users;
SET FOREIGN_KEY_CHECKS = 1;

-- =================================================================================
-- 1. 가상 회원 (Users) - 100만 명 더미데이터 적재 대상
-- =================================================================================
CREATE TABLE users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '유저 고유 ID',
    email       VARCHAR(100) NOT NULL COMMENT '이메일',
    name        VARCHAR(50)  NOT NULL COMMENT '이름 (응답 시 마스킹: 홍*동)',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '가입 일시',

    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='사용자 테이블';

-- =================================================================================
-- 2. 쿠폰 정책 (Coupon Policy) - 삼각 정합성의 절대 기준값
-- =================================================================================
CREATE TABLE coupon_policy (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '쿠폰 정책 고유 ID',
    title             VARCHAR(100) NOT NULL COMMENT '쿠폰명',
    coupon_type       VARCHAR(20)  NOT NULL COMMENT '할인 유형 (RATE: 정률, FIXED: 정액)',
    discount_value    INT          NOT NULL COMMENT '할인 금액 또는 할인율',
    total_quantity    INT          NOT NULL COMMENT '총 발행 수량 (삼각 정합성 기준 불변값)',

    -- 실시간 카운터가 아니며, 검증 배치가 사후에 채워넣는 조회/스냅샷 전용 값 (핫 로우 경합 방지)
    issued_quantity   INT          NOT NULL DEFAULT 0 COMMENT '현재 DB 발급 완료 수량 (배치 집계용)',
    open_at           DATETIME     NOT NULL COMMENT '발급 시작 일시 (캐시 워밍업 기준)',
    close_at          DATETIME     NULL     COMMENT '발급 마감 일시',

    -- 정책 삭제: 소프트 삭제 (Redis 먼저 삭제 → DB deleted_at 기록)
    deleted_at        DATETIME     NULL     COMMENT '삭제 일시 (소프트 딜리트)',

    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '정책 등록 일시',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '정책 수정 일시',

    KEY idx_policy_open_at (open_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='쿠폰 정책 마스터 테이블';

-- =================================================================================
-- 3. 쿠폰 발급 (Coupon Issue) - 300만 건 대용량 핵심 테이블!
-- =================================================================================
CREATE TABLE coupon_issue (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '발급 고유 ID',
    coupon_policy_id    BIGINT       NOT NULL COMMENT '쿠폰 정책 ID (FK)',
    user_id             BIGINT       NOT NULL COMMENT '발급 유저 ID (FK)',
    receipt_id          VARCHAR(64)  NOT NULL COMMENT '202 비동기 접수 번호 (rcpt_xxxx)',

    -- 상태는 ISSUED(발급, 사용가능) / USED(사용됨) / EXPIRED(만료) 3종만 존재
    -- 사용 취소 시 CANCELED로 가지 않고 다시 ISSUED로 복귀 (used_at은 NULL로 복귀)
    status              VARCHAR(20)  NOT NULL DEFAULT 'ISSUED' COMMENT '현재 상태 (ISSUED/USED/EXPIRED)',

    issued_at           DATETIME     NOT NULL COMMENT '발급 완료 일시',
    used_at             DATETIME     NULL     COMMENT '사용 일시 (사용 취소 시 NULL로 복구)',
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 일시',

    -- [★ 핵심 인덱스 1] 1인 1매 DB 최후 방어선 (Kafka 중복 소비 원천 차단)
    CONSTRAINT uk_policy_user UNIQUE (coupon_policy_id, user_id),

    -- [★ 핵심 인덱스 2 & 3] 내 쿠폰함 1ms 조회 및 300만 건 무부하 인덱스 스캔
    KEY idx_issue_user (user_id),
    KEY idx_issue_status (coupon_policy_id, status),
    KEY idx_policy_issued (coupon_policy_id, issued_at),

    CONSTRAINT fk_issue_policy FOREIGN KEY (coupon_policy_id) REFERENCES coupon_policy(id),
    CONSTRAINT fk_issue_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_issue_status CHECK (status IN ('ISSUED', 'USED', 'EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='쿠폰 발급 테이블';

-- =================================================================================
-- 4. 쿠폰 상태 변경 이력 (Coupon History) - 불변 Append-Only 감사 로그
-- =================================================================================
CREATE TABLE coupon_history (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '이력 고유 ID',
    coupon_issue_id   BIGINT       NOT NULL COMMENT '쿠폰 발급 ID (FK)',

    -- 클라이언트가 보낸 멱등성 키 (Idempotency-Key)
    request_id        VARCHAR(64)  NOT NULL COMMENT '요청 멱등성 추적 키',
    prev_status       VARCHAR(20)  NOT NULL COMMENT '이전 상태 (ISSUED/USED/EXPIRED)',
    new_status        VARCHAR(20)  NOT NULL COMMENT '변경 후 상태 (ISSUED/USED/EXPIRED)',
    cancel_reason     VARCHAR(255) NULL     COMMENT '사용 취소 사유 (API: cancelReason)',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '이력 기록 일시',

    -- [★ 멱등성 방어] 동일 요청 중복 반영 영구 방지 (FR-13 멱등성 보장)
    UNIQUE KEY uk_history_request (request_id),
    KEY idx_history_issue (coupon_issue_id),

    CONSTRAINT fk_history_issue FOREIGN KEY (coupon_issue_id) REFERENCES coupon_issue(id) ON DELETE CASCADE,
    CONSTRAINT chk_history_status CHECK (
        prev_status IN ('ISSUED', 'USED', 'EXPIRED') AND new_status IN ('ISSUED', 'USED', 'EXPIRED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='쿠폰 상태 변경 불변 감사 로그 테이블';

-- =================================================================================
-- 5. 재처리 큐 (Reconciliation Log) - Kafka 발행 실패건 재발행 / 수동 재처리 / DLT / Redis 복구
-- =================================================================================
CREATE TABLE reconciliation_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '로그 고유 ID',

    -- EVENT_REPUBLISH(Kafka 발행 실패건 재발행) / ISSUE_REPROCESS(상태 불일치 수동 재처리)
    -- DLT_REPROCESS(DLT 재처리) / REDIS_RECOVER(Redis 완전 유실 복구)
    type              VARCHAR(20)  NOT NULL COMMENT '재처리 유형',

    event_key         VARCHAR(64)  NULL     COMMENT '재발행 멱등키 (eventId)',
    coupon_issue_id   BIGINT       NULL     COMMENT '대상 발급 ID (issueId)',

    topic             VARCHAR(100) NULL     COMMENT '대상 Kafka 토픽',
    payload           JSON         NULL     COMMENT '재발행할 원본 이벤트 JSON payload',

    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '재처리 상태 (PENDING/SUCCESS/FAILED)',
    fail_reason       VARCHAR(255) NULL     COMMENT '실패 사유',
    retry_count       INT          NOT NULL DEFAULT 0 COMMENT '재시도 횟수',

    requested_by      VARCHAR(50)  NULL     COMMENT '요청 관리자',
    processed_at      DATETIME     NULL     COMMENT '처리 완료 일시',

    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '등록 일시',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정 일시',

    UNIQUE KEY uk_reconciliation_event_key (event_key),
    KEY idx_reconciliation_issue (coupon_issue_id),
    KEY idx_reconciliation_status (status, type),

    CONSTRAINT fk_reconciliation_issue FOREIGN KEY (coupon_issue_id) REFERENCES coupon_issue(id),
    CONSTRAINT chk_reconciliation_type CHECK (
        type IN ('EVENT_REPUBLISH', 'ISSUE_REPROCESS', 'DLT_REPROCESS', 'REDIS_RECOVER')
    ),
    CONSTRAINT chk_reconciliation_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='재처리 큐 및 자가치유 로그 테이블';

-- =================================================================================
-- 6. 정합성 검증 리포트 (Verification Report) - 300만 건 삼각 정합성 감사용
-- =================================================================================
CREATE TABLE verification_report (
    id                BIGINT       AUTO_INCREMENT PRIMARY KEY COMMENT '리포트 고유 ID',
    coupon_policy_id  BIGINT       NOT NULL COMMENT '검증 대상 쿠폰 정책 ID (FK)',
    run_at            DATETIME     NOT NULL COMMENT '검증 실행 일시',
    total_issued      INT          NOT NULL COMMENT 'DB coupon_issue 기준 발급 집계 수',
    total_reserved    INT          NOT NULL COMMENT 'Redis RESERVED 기준 검증 시점 스냅샷 수',
    mismatch_count    INT          NOT NULL COMMENT '정합성 불일치 오차 건수 (0이어야 정상)',
    status            VARCHAR(20)  NOT NULL COMMENT '상태 (SUCCESS / MISMATCH_FOUND)',
    report_url        VARCHAR(255) NULL     COMMENT '불일치 상세 내역 CSV 파일 경로',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '리포트 생성 일시',

    KEY idx_verification_policy (coupon_policy_id, run_at),

    CONSTRAINT fk_verification_policy FOREIGN KEY (coupon_policy_id) REFERENCES coupon_policy(id),
    CONSTRAINT chk_verification_status CHECK (status IN ('SUCCESS', 'MISMATCH_FOUND'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='300만 건 삼각 정합성 검증 리포트 테이블';