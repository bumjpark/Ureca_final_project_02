-- =================================================================================
-- 정합성 검증 배치 비동기 전환: verification_report.status에 PENDING 추가
-- (UBM-22)
-- 요청 즉시 PENDING으로 리포트 row를 선-저장하고, 백그라운드 처리가 끝나면
-- SUCCESS/MISMATCH_FOUND로 갱신한다. VerificationStatus는 현재 검증 배치
-- 엔드포인트 외에는 다른 팀원 코드가 참조하지 않아 이 변경의 영향 범위는 여기 국한된다.
-- =================================================================================

USE coupon_db;

ALTER TABLE verification_report
    DROP CHECK chk_verification_status;

ALTER TABLE verification_report
    ADD CONSTRAINT chk_verification_status CHECK (status IN ('PENDING', 'SUCCESS', 'MISMATCH_FOUND'));
