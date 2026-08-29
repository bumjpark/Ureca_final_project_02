-- 부하테스트용 가상 유저 시딩.
--
-- burst-20k.js는 MAX_USER_ID를 지정하지 않으면 GET /api/users의 totalElements로 상한을
-- 자동 인식하고, userId를 1..N에 결정론적으로 배정한다. 그래서 id가 1부터 빈틈없이 이어져야
-- "같은 userId가 두 번 뽑히는 일이 없다"는 전제가 성립한다 — 재귀 CTE로 1..N을 그대로 만든다.
--
-- 사용법(기본 20,000명):
--   docker exec -i coupon-mysql mysql -uroot -p<pw> --default-character-set=utf8mb4 \
--       coupon_db < scripts/seed-users.sql
-- 인원을 바꾸려면 아래 @user_count만 수정한다.
--
-- `--default-character-set=utf8mb4`를 빠뜨리지 말 것. mysql 클라이언트가 입력을 latin1로
-- 해석해 한글 이름이 이중 인코딩된 채로 저장된다(테스트유저1 → í…ŒìŠ¤íŠ¸ìœ ì €1).
-- 컬럼과 테이블은 이미 utf8mb4라 스키마 문제가 아니라 순전히 클라이언트 설정 문제다.
--
-- 주의: 이 스크립트는 users를 비우고 다시 채운다. TRUNCATE가 아니라 DELETE를 쓰는 이유는 두
-- 가지다. (1) MySQL은 FK로 참조되는 테이블은 참조 행이 하나도 없어도 TRUNCATE를 거부한다
-- (coupon_issue.fk_issue_user). (2) DELETE는 실제로 참조하는 발급 데이터가 남아있으면 FK
-- 위반으로 실패하는데, 그게 우리가 원하는 안전장치다 — 발급 이력이 있는 DB를 조용히 밀지
-- 않는다. 정말 초기화하려면 발급 데이터부터 정리하고 다시 실행할 것.

SET @user_count = 20000;

-- 재귀 CTE 기본 깊이 제한(1000)을 넘겨야 하므로 필요한 만큼 올린다.
SET SESSION cte_max_recursion_depth = 1000000;

DELETE FROM users;
ALTER TABLE users AUTO_INCREMENT = 1;

INSERT INTO users (id, email, name, created_at)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < @user_count
)
SELECT
    n,
    CONCAT('loadtest', LPAD(n, 7, '0'), '@example.com'),
    CONCAT('테스트유저', n),
    NOW()
FROM seq;

SELECT COUNT(*) AS seeded_users, MIN(id) AS min_id, MAX(id) AS max_id FROM users;
