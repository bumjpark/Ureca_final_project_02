-- =================================================================================
-- 선착순 발급 순서 검증(FCFS) 근거 데이터: 대기열 진입 순서 영속화
--
-- 큐 ZSET(coupon:policy:{id}:queue)은 QueueAdmissionService가 ZPOPMIN으로 뽑는 즉시
-- 항목이 사라지므로, 검증 시점(발급이 끝난 뒤)에는 "누가 몇 번째로 줄 섰는지" 다시 조회할
-- 수단이 없다. queue-join-events Kafka 토픽을 소비해 여기 영속화한다(컨슈머는 별도 작업).
--
-- queue_rank는 반드시 join_queue.lua의 seq(INCR, 절대·불변 순번)여야 한다 — QueueJoinEvent.rank()는
-- ZRANK 기반이라 스케줄러가 앞사람을 계속 ZPOPMIN으로 빼가면서 값이 흔들리므로 전역 순서 기준으로 쓸 수 없다.
-- =================================================================================

USE coupon_db;

CREATE TABLE queue_join_log (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '로그 고유 ID',
    coupon_policy_id  BIGINT       NOT NULL COMMENT '쿠폰 정책 ID',
    user_id           BIGINT       NOT NULL COMMENT '대기열 진입 유저 ID',
    status            VARCHAR(20)  NOT NULL COMMENT 'join 시점 상태 (WAITING/ADMITTED)',
    queue_rank        BIGINT       NOT NULL COMMENT 'join_queue.lua seq(INCR) 기반 절대 선착순 번호표. 전역 순서 판단 기준 — ZRANK 아님',
    joined_at         DATETIME     NOT NULL COMMENT '대기열 진입 일시',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '로그 적재 일시',

    -- join_queue.lua 5단계(멱등 재조인)에서 같은 유저가 여러 번 join해도 이벤트가 중복
    -- 발행될 수 있음 — 컨슈머는 최초 1건만 유지(INSERT IGNORE 또는 upsert-최소값).
    UNIQUE KEY uq_policy_user (coupon_policy_id, user_id),
    KEY idx_policy_rank (coupon_policy_id, queue_rank),

    CONSTRAINT chk_queue_join_log_status CHECK (status IN ('WAITING', 'ADMITTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='대기열 진입 순서 영속화 (FCFS 검증 근거 데이터, queue-join-events 토픽 소비 결과)';
