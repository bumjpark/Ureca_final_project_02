-- KEYS[1] = 실시간 재고 (coupon:policy:{policyId}:stock)
-- KEYS[2] = 임시 예약 ZSET (coupon:policy:{policyId}:reserved)
-- KEYS[3] = 발급 완료 SET (coupon:policy:{policyId}:issued)
-- KEYS[4] = 입장 후 미확정 ZSET (coupon:policy:{policyId}:pending) — admit_batch.lua가 채움
-- ARGV[1] = userId
-- ARGV[2] = timestamp

-- 0. pending(입장 후 미확정) 상태를 해제한다 — 결과가 성공이든 실패든, 이 스크립트가 호출된
--    시점부터는 더 이상 "입장은 했지만 /issue를 안 부른 사람"이 아니다. QueueAdmissionScheduler는
--    이 ZSET 크기를 "다음 입장 배치에서 덜 뽑아야 할 인원"으로 쓰므로(재고 자체는 아래에서
--    그대로 차감한다 — 이 줄은 재고 예약이 아니라 입장 스케줄러의 계산 정확도를 위한 것이다),
--    여기서 안 지우면 이미 끝난 사람 몫만큼 다음 배치가 계속 과소 계산된다. admit_batch.lua를
--    거치지 않은 호출(예: 관리자 시연, 기존 테스트가 activeToken을 직접 세팅하는 경우)에도
--    안전하다 — 존재하지 않는 멤버에 대한 ZREM은 그냥 0을 반환하는 무해한 no-op이다.
redis.call('ZREM', KEYS[4], ARGV[1])

-- 1. 중복 확인 (issued SET 또는 reserved ZSET에 유저가 있는지)
local isIssued = redis.call('SISMEMBER', KEYS[3], ARGV[1])
if isIssued == 1 then
    return 409  -- 409: 이미 발급받았거나 예약됨
end

local isReserved = redis.call('ZSCORE', KEYS[2], ARGV[1])
if isReserved ~= false then
    return 409 -- 409: 이미 발급받았거나 예약됨
end

-- 2. 재고 확인 (Fast-Fail)
local stock = redis.call('GET', KEYS[1])
if not stock or tonumber(stock) <= 0 then
    return 400
end

-- 3. 재고 원자적 차감 후 음수 방어 롤백
--    Lua는 단일 스레드로 원자적 실행되지만, 외부 CLI 등 비정상 경로로
--    stock 이 직접 조작될 경우를 대비해 DECR 후 음수이면 즉시 INCR 원복.
local remaining = redis.call('DECR', KEYS[1])
if remaining < 0 then
    redis.call('INCR', KEYS[1])
    return 400
end

-- 4. RESERVED ZSET에 임시 예약 등록
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])

return 200