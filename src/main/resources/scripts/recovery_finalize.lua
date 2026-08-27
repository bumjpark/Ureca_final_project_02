-- Redis 재구성(완전 유실 복구, E) 마무리 단계.
-- 서비스가 실제로 읽는 키 3개(stock/reserved/issued)를 한 스크립트 안에서 교체한다.
-- Redis는 스크립트 실행 중 다른 클라이언트 명령을 끼워넣지 않으므로, 이 세 키 중 일부만
-- 바뀐 "중간 상태"가 정상 트래픽에 노출되는 순간은 생기지 않는다.
--
-- KEYS[1] = 실시간 재고 키 (coupon:policy:{id}:stock)
-- KEYS[2] = 임시 예약 ZSET 키 (coupon:policy:{id}:reserved)
-- KEYS[3] = 발급 완료 SET 키 (coupon:policy:{id}:issued) — 실제 서비스 키
-- KEYS[4] = 재구성 결과를 미리 채워둔 staging SET 키 (coupon:policy:{id}:issued:staging)
-- ARGV[1] = DB 기준으로 재계산한 remainingStock

-- Redis가 완전히 유실된 상황을 전제로 하므로, 아직 확정되지 못했던 예약(reserved)은
-- 근거를 잃은 것으로 보고 비운다.
redis.call('DEL', KEYS[2])

-- staging에 값이 있으면(발급자가 1명 이상) 그걸로 실제 키를 통째로 교체하고,
-- 없으면(발급자가 0명) 실제 키를 그냥 비운다. RENAME은 원본 키가 없으면 에러를 내므로
-- EXISTS로 먼저 분기한다.
if redis.call('EXISTS', KEYS[4]) == 1 then
    redis.call('RENAME', KEYS[4], KEYS[3])
else
    redis.call('DEL', KEYS[3])
end

redis.call('SET', KEYS[1], ARGV[1])

return 1
