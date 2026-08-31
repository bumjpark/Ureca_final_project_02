-- =================================================================================
-- coupon_policy 테이블에 status 컬럼 추가 및 상태 도메인 제약 설정.
-- 상태 도메인: BEFORE_OPEN(오픈전), OPEN(오픈), CLOSED(마감), EXPIRED(만료), DELETED(삭제)
-- =================================================================================

USE coupon_db;

ALTER TABLE coupon_policy
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT '쿠폰 정책 상태 (BEFORE_OPEN/OPEN/CLOSED/EXPIRED/DELETED)';

ALTER TABLE coupon_policy
    ADD CONSTRAINT chk_policy_status CHECK (status IN ('BEFORE_OPEN', 'OPEN', 'CLOSED', 'EXPIRED', 'DELETED'));

-- 기존 레코드들의 현재 기준 초기 상태 보정
UPDATE coupon_policy
SET status = 'DELETED'
WHERE deleted_at IS NOT NULL;

UPDATE coupon_policy
SET status = 'EXPIRED'
WHERE deleted_at IS NULL AND close_at IS NOT NULL AND close_at <= NOW();

UPDATE coupon_policy
SET status = 'BEFORE_OPEN'
WHERE deleted_at IS NULL AND (close_at IS NULL OR close_at > NOW()) AND open_at > NOW();

UPDATE coupon_policy
SET status = 'OPEN'
WHERE deleted_at IS NULL AND (close_at IS NULL OR close_at > NOW()) AND open_at <= NOW();
