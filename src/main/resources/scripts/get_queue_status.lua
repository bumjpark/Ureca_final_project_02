-- KEYS[1] = active_user     (active_user:{policyId}:{userId})
-- KEYS[2] = issued SET       (coupon:policy:{id}:issued)
-- KEYS[3] = stock            (coupon:policy:{id}:stock)
-- KEYS[4] = queue ZSET        (coupon:policy:{id}:queue)
-- KEYS[5] = admitted_marker (admitted_marker:{policyId}:{userId})
-- ARGV[1] = userId (string)
-- 반환: {status, token, rank}
--   {"ADMITTED", token, "0"}    : 입장 허가됨 (activeToken 발급됨)
--   {"WAITING", "", rank}       : 대기 중 (내 앞 대기 순번)
--   {"SOLD_OUT", "", "-1"}      : 재고 소진 (품절)
--   {"ISSUED", "", "-1"}        : 이미 발급 완료
--   {"EXPIRED", "", "-1"}       : 입장 허가 후 토큰 만료됨 (재진입 필요)
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
-- SOLD_OUT을 알려주는 동시에 대기열(ZSET)에서도 자신을 뺀다. 그냥 응답만 주고 두면 실제로는
-- 이미 통보받고 떠난 유저가 ZSET에 그대로 남아 다음 정합성/운영 확인 때 "아직 대기 중"으로
-- 오인되고, 24시간 TTL로 키 전체가 만료될 때까지 방치된다(실측: 부하테스트 종료 후 이미
-- SOLD_OUT을 받고 빠져나간 유저 1,700여 명이 대기열에 그대로 쌓여있던 것을 확인). ZREM은
-- 멤버가 없어도 0을 반환하는 무해한 no-op이라 admit_batch.lua가 이미 뽑아간 유저에게 다시
-- 호출돼도 안전하다.
local stock = redis.call('GET', KEYS[3])
if stock and tonumber(stock) <= 0 then
    redis.call('ZREM', KEYS[4], ARGV[1])
    return {"SOLD_OUT", "", "-1"}
end

-- 4. 대기열 순번(ZRANK) 확인 및 조기 탈출 (Early Exit)
local rank = redis.call('ZRANK', KEYS[4], ARGV[1])
if rank ~= false then
    local rankNum = tonumber(rank)
    if stock and rankNum >= math.floor(tonumber(stock) * 1.1) then
        redis.call('ZREM', KEYS[4], ARGV[1])
        return {"SOLD_OUT", "", "-1"}
    end
    return {"WAITING", "", tostring(rank)}
end

-- 5. 입장 허가 후 토큰이 만료된 유저인지 확인 (마커 키 존재 여부)
if KEYS[5] then
    local hasAdmittedMarker = redis.call('GET', KEYS[5])
    if hasAdmittedMarker ~= false then
        return {"EXPIRED", "", "-1"}
    end
end

-- 6. 대기열에 존재하지 않음 (미등록 유저)
return {"NOT_FOUND", "", "-1"}
