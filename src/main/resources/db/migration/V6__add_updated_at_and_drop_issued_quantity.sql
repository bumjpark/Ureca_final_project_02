-- =================================================================================
-- 1) updated_at 컬럼이 없는 테이블(coupon_history, users, verification_report)에 추가.
--    실무 컨벤션에 맞춰 모든 테이블에 생성/수정 일시를 남긴다.
--
-- 2) coupon_policy.issued_quantity 컬럼 제거.
--    원래는 검증 배치(VerificationAsyncTrigger)가 DB 발급 집계 수량으로 동기화해줄
--    예정이었으나(CouponPolicy.syncIssuedQuantity()) 실제로 그 호출이 연결된 적이 없어
--    항상 0으로 고정되어 있었다. 실시간 발급 현황은 CouponStatusService가 Redis 기준으로
--    별도 계산하므로 이 컬럼과 무관하며, 계속 죽은 값으로 API에 노출하는 대신 제거한다.
-- =================================================================================

USE coupon_db;

ALTER TABLE coupon_history
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        COMMENT '이력 수정 일시' AFTER created_at;

ALTER TABLE users
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        COMMENT '유저 정보 수정 일시' AFTER created_at;

ALTER TABLE verification_report
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        COMMENT '리포트 수정 일시' AFTER created_at;

ALTER TABLE coupon_policy
    DROP COLUMN issued_quantity;
