-- =================================================================================
-- 정합성 검증 300만 건 규모 실측 (2026-08-30, 현재 시스템 기준 — Check C/FCFS 포함)
--
-- 2026-08-27 테스트(Verification-Batch-1M-Scale-Test.md)와 차이점: 그때는 queue_join_log가
-- 컨슈머 미구현으로 비어있어 Check C(FCFS)가 스킵됐다. 지금은 QueueJoinLogWriter가 실제로
-- 동작하므로, 이 스크립트는 queue_join_log까지 3,000,000건 채워 Check C도 실제로 돌게 한다.
-- 완전히 격리된 임시 스택(docker-compose.scale3m.yml)에서만 실행할 것 — 라이브 개발 DB에는
-- 손대지 않는다.
-- =================================================================================

USE coupon_db;

-- ---------------------------------------------------------------------------------
-- 1. 유저 3,000,000명
-- ---------------------------------------------------------------------------------
INSERT INTO users (email, name, created_at, updated_at)
SELECT CONCAT('seed-user-', n, '@test.com'), CONCAT('seed-user-', n), NOW(), NOW()
FROM (
    SELECT @u := @u + 1 AS n
    FROM information_schema.columns c1, information_schema.columns c2, (SELECT @u := 0) init
    LIMIT 3000000
) t;

SET @user_min_id = (SELECT MIN(id) FROM users WHERE email LIKE 'seed-user-%@test.com');

-- ---------------------------------------------------------------------------------
-- 2. 정책 1개, 재고 3,000,000 (전량 소진 시나리오)
-- ---------------------------------------------------------------------------------
INSERT INTO coupon_policy (title, coupon_type, discount_value, total_quantity, open_at, close_at, created_at, updated_at)
VALUES ('clean-3m-with-fcfs', 'FIXED', 1000, 3000000, NOW(), '2026-12-31 23:59:59', NOW(), NOW());

SET @policy_id = LAST_INSERT_ID();

-- ---------------------------------------------------------------------------------
-- 3. 발급(coupon_issue) 3,000,000건 — 유저 전원에게 1건씩, issued_at을 살짝 흩어서
--    현실적인 초당 발급 그래프 조회도 가능하게 한다.
-- ---------------------------------------------------------------------------------
INSERT INTO coupon_issue (coupon_policy_id, user_id, receipt_id, status, issued_at, created_at, updated_at)
SELECT @policy_id, id, CONCAT('rcpt_seed_', id),
       'ISSUED',
       DATE_SUB(NOW(), INTERVAL (3000000 - (id - @user_min_id)) SECOND),
       NOW(), NOW()
FROM users
WHERE email LIKE 'seed-user-%@test.com';

-- ---------------------------------------------------------------------------------
-- 4. 이력(coupon_history) 3,000,000건 — 전부 최초 발급(NONE -> ISSUED), 생명주기 이상 없음
-- ---------------------------------------------------------------------------------
INSERT INTO coupon_history (coupon_issue_id, request_id, prev_status, new_status, created_at)
SELECT id, receipt_id, 'NONE', 'ISSUED', issued_at
FROM coupon_issue
WHERE coupon_policy_id = @policy_id;

-- ---------------------------------------------------------------------------------
-- 5. 대기열 진입 로그(queue_join_log) 3,000,000건 — Check C(FCFS) 전제조건.
--    queue_rank를 user_id 순서 그대로 1..3,000,000으로 매겨, "도착 순번 상위 N명 == 실제
--    발급자 전원"이 되도록 완전히 일치시킨다(FCFS 위반 0건 시나리오).
-- ---------------------------------------------------------------------------------
INSERT INTO queue_join_log (coupon_policy_id, user_id, status, queue_rank, joined_at, created_at)
SELECT @policy_id, id, 'ADMITTED', (id - @user_min_id + 1),
       DATE_SUB(NOW(), INTERVAL (3000000 - (id - @user_min_id)) SECOND),
       NOW()
FROM users
WHERE email LIKE 'seed-user-%@test.com';

SELECT @policy_id AS seeded_policy_id, @user_min_id AS user_min_id;
