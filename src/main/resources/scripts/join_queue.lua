-- KEYS[1] = issued SET        (coupon:policy:{id}:issued)
-- KEYS[2] = reserved ZSET     (coupon:policy:{id}:reserved)
-- KEYS[3] = stock              (coupon:policy:{id}:stock)
-- KEYS[4] = queue ZSET         (coupon:policy:{id}:queue)
-- KEYS[5] = queue seq counter  (coupon:policy:{id}:queue:seq)
-- ARGV[1] = userId (string)
-- ARGV[2] = maxQueueSize (string)
-- 반환: {statusCode, rank, queueLen}
--   201 = 즉시 입장 ADMITTED (대기열에 대기자가 없어 ZSET 등록 없이 즉시 통과)
--   200 = 대기 등록 WAITING (rank = 내 앞 대기 인원, queueLen = 현재 대기열 크기)
--   400 = 재고 소진 (Fast-Fail)
--   409 = 이미 발급/예약 중 (중복 차단)
--   500 = stock 키 미초기화 (이벤트 준비 안 됨)
--   503 = 대기열 정원 초과 (Fast-Fail)

-- 1. 이미 발급 완료된 유저 차단 (ISSUED SET)
local isIssued = redis.call('SISMEMBER', KEYS[1], ARGV[1])
if isIssued == 1 then
    return {409, -1, -1}
end

-- 2. 임시 예약(RESERVED) 상태인 유저 차단 — 발급 직전 단계
local isReserved = redis.call('ZSCORE', KEYS[2], ARGV[1])
if isReserved ~= false then
    return {409, -1, -1}
end

-- 3. 재고 키 존재 여부 및 재고량 확인
local stock = redis.call('GET', KEYS[3])
if not stock then
    return {500, -1, -1}
end
if tonumber(stock) <= 0 then
    return {400, -1, -1}
end

-- 4. 이미 대기열에 등록된 유저인지 확인 — 기존 순번 그대로 반환 (멱등성 보장)
local existingScore = redis.call('ZSCORE', KEYS[4], ARGV[1])
if existingScore ~= false then
    local rank = redis.call('ZRANK', KEYS[4], ARGV[1])
    local totalInQueue = redis.call('ZCARD', KEYS[4])
    return {200, rank, totalInQueue}
end

-- 5. 대기열 정원 초과 Fast-Fail
local queueSize = redis.call('ZCARD', KEYS[4])
if queueSize >= tonumber(ARGV[2]) then
    return {503, -1, -1}
end

-- 6. 즉시 입장 vs 대기열 등록 분기
-- 대기열이 비어있는 경우: ZSET에 유령 등록하지 않고 즉시 통과 (201 ADMITTED)
if queueSize == 0 then
    -- TTL 설정 (이벤트 후 고아 키 방지)
    redis.call('EXPIRE', KEYS[4], 86400)
    redis.call('EXPIRE', KEYS[5], 86400)
    return {201, 0, 0}
end

-- 대기열에 이미 대기자가 있는 경우: ZSET에 순차 등록 후 WAITING 반환
local seq = redis.call('INCR', KEYS[5])
redis.call('ZADD', KEYS[4], seq, ARGV[1])
local rank = redis.call('ZRANK', KEYS[4], ARGV[1])
local totalInQueue = redis.call('ZCARD', KEYS[4])

-- TTL 설정
redis.call('EXPIRE', KEYS[4], 86400)
redis.call('EXPIRE', KEYS[5], 86400)

return {200, rank, totalInQueue}
