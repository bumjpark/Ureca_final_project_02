-- =================================================================================
-- coupon_issue, coupon_history의 PK 생성 전략을 IDENTITY → SEQUENCE로 전환하기 위한
-- 시퀀스 테이블 생성.
--
-- 배경: IDENTITY 전략은 INSERT 직후 생성된 키를 즉시 조회해야 해서, Hibernate가
-- 매 save() 호출마다 즉시 flush(=DB 왕복)를 강제한다. Kafka Consumer가 이벤트 1건당
-- coupon_issue/coupon_history 2번 INSERT를 매번 즉시 flush하다 보니, 대량 처리 시
-- DB 왕복 횟수가 병목이 된다.
--
-- SEQUENCE는 ID를 미리 allocationSize(50)개씩 클라이언트가 캐싱해서 쓰기 때문에,
-- INSERT 전에 ID를 이미 알고 있어 즉시 flush가 필요 없다 — 트랜잭션 커밋 시점에
-- 한 번에 flush할 수 있어 왕복 횟수가 줄어든다.
--
-- MySQL은 네이티브 SEQUENCE가 없어서, Hibernate가 이를 "시퀀스 전용 테이블
-- (next_val 컬럼 하나짜리)"로 흉내 낸다(TableStructure). 테이블명은
-- @SequenceGenerator(sequenceName=...)과 정확히 일치해야 하고, 컬럼명은
-- Hibernate 기본값인 next_val 이어야 한다.
--
-- next_val 초기값은 기존 IDENTITY로 발급된 최대 ID(운영 데이터 + 테스트 데이터 포함)보다
-- 충분히 크게 잡아 PK 충돌을 방지한다.
-- =================================================================================

USE coupon_db;

CREATE TABLE coupon_issue_seq (
    next_val BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='coupon_issue PK 시퀀스 (MySQL 네이티브 SEQUENCE 미지원으로 Hibernate가 테이블로 흉내)';

INSERT INTO coupon_issue_seq (next_val) VALUES (100000);

CREATE TABLE coupon_history_seq (
    next_val BIGINT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='coupon_history PK 시퀀스 (MySQL 네이티브 SEQUENCE 미지원으로 Hibernate가 테이블로 흉내)';

INSERT INTO coupon_history_seq (next_val) VALUES (100000);
