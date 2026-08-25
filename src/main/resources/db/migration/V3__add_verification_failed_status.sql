-- =================================================================================
-- 비동기 검증 배치가 실행 도중 실패하면 리포트가 PENDING에 영원히 머무르는 문제 수정.
-- FAILED 상태를 추가하고, 실패 원인을 남길 수 있게 컬럼을 하나 더 둔다.
-- (UBM-23)
-- =================================================================================

USE coupon_db;

ALTER TABLE verification_report
    DROP CHECK chk_verification_status;

ALTER TABLE verification_report
    ADD CONSTRAINT chk_verification_status
        CHECK (status IN ('PENDING', 'SUCCESS', 'MISMATCH_FOUND', 'FAILED'));

ALTER TABLE verification_report
    ADD COLUMN failure_reason VARCHAR(500) NULL COMMENT '비동기 검증 실패 시 예외 메시지 요약' AFTER report_url;
