-- KEYS[1] = 실시간 재고 (coupon:policy:{policyId}:stock)
-- KEYS[2] = 임시 예약 ZSET (coupon:policy:{policyId}:reserved)
-- KEYS[3] = 발급 완료 SET (coupon:policy:{policyId}:issued)
-- ARGV[1] = userId
-- ARGV[2] = timestamp


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