-- KEYS[1] = active_user (active_user:{policyId}:{userId})
-- KEYS[2] = issued SET   (coupon:policy:{id}:issued)
-- KEYS[3] = stock        (coupon:policy:{id}:stock)
-- KEYS[4] = queue ZSET    (coupon:policy:{id}:queue)
-- ARGV[1] = userId (string)
-- 반환: {status, token, rank}
--   {"ADMITTED", token, "0"}    : 입장 허가됨 (activeToken 발급됨)
--   {"WAITING", "", rank}       : 대기 중 (내 앞 대기 순번)
--   {"SOLD_OUT", "", "-1"}      : 재고 소진 (품절)
--   {"ISSUED", "", "-1"}        : 이미 발급 완료
--   {"NOT_FOUND", "", "-1"}     : 대기열 등록 이력 없음

-- 1. 활성 토큰 발급 여부 확인 (내 차례가 되어 통과한 상태)
local token = redis.call('GET', KEYS[1])
if token ~= false then
    return {"ADMITTED", token, "0"}
end

-- 2. 이미 발급 완료된 유저인지 확인
local isIssued = redis.call('SISMEMBER', KEYS[2], ARGV[1])
if isIssued == 1 then
    return {"ISSUED", "", "-1"}
end

-- 3. 재고 소진 여부 확인 (품절)
local stock = redis.call('GET', KEYS[3])
if stock and tonumber(stock) <= 0 then
    return {"SOLD_OUT", "", "-1"}
end

-- 4. 대기열 순번(ZRANK) 확인
local rank = redis.call('ZRANK', KEYS[4], ARGV[1])
if rank ~= false then
    return {"WAITING", "", tostring(rank)}
end

-- 5. 대기열에 존재하지 않음 (미등록 또는 만료 이탈)
return {"NOT_FOUND", "", "-1"}
