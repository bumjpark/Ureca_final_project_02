-- =================================================================================
-- coupon_history.chk_history_status가 prev_status를 ('ISSUED','USED','EXPIRED')로만 제한하고 있어,
-- 최초 발급 시 사용하는 HistoryPrevStatus.NONE(이전 상태 없음)이 체크 제약을 위반해
-- 모든 최초 발급 이력 INSERT가 실패하는 문제 수정.
-- (Kafka Consumer 이력 반영 테스트 중 발견)
-- =================================================================================

USE coupon_db;

ALTER TABLE coupon_history
    DROP CHECK chk_history_status;

ALTER TABLE coupon_history
    ADD CONSTRAINT chk_history_status
        CHECK (
            prev_status IN ('NONE', 'ISSUED', 'USED', 'EXPIRED')
            AND new_status IN ('ISSUED', 'USED', 'EXPIRED')
        );
