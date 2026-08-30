-- KEYS[1] = 실시간 재고            (coupon:policy:{policyId}:stock)
-- KEYS[2] = 대기열 ZSET            (coupon:policy:{policyId}:queue)
-- KEYS[3] = 입장 후 미확정 ZSET    (coupon:policy:{policyId}:pending)
-- ARGV[1] = 이번 틱에 뽑을 배치 크기(batchSize)
-- ARGV[2] = 현재 시각(epoch millis) — pending ZSET의 score로 씀
-- 반환: 이번에 입장시킨 userId 문자열 배열(빈 배열이면 0명)
--
-- 재고를 여기서 깎지 않는다 — 재고 차감/Fast-Fail cap은 여전히 issue_coupon.lua 한 곳의
-- 책임이다(2026-08-29까지의 초과발급 0건 증명 테스트를 그대로 보존하기 위한 설계 선택).
-- 이 스크립트가 하는 일은 "이번 틱에 몇 명을 더 뽑아도 안전한가"를 재고와 별개로 계산하는 것뿐이다.
--
-- available = stock - pending개수.
-- pending은 "입장은 했지만 아직 /issue를 부르지 않은 사람 수"라, 이 값을 빼지 않으면 매 틱마다
-- 같은 재고를 놓고 여러 배치가 겹쳐서 경쟁하게 된다 — 이게 실측으로 확인한 FCFS 역전의 원인이었다.

local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
local pendingCount = redis.call('ZCARD', KEYS[3])
local available = stock - pendingCount

if available <= 0 then
    return {}
end

local batchSize = tonumber(ARGV[1])
local actualBatch = math.min(batchSize, available)
if actualBatch <= 0 then
    return {}
end

local popped = redis.call('ZPOPMIN', KEYS[2], actualBatch)
if #popped == 0 then
    return {}
end

local now = ARGV[2]
local result = {}
for i = 1, #popped, 2 do
    local userId = popped[i]
    redis.call('ZADD', KEYS[3], now, userId)
    table.insert(result, userId)
end

return result
