-- KEYS[1] = 대상 SET 키
-- ARGV[1] = 시작값(포함), ARGV[2] = 끝값(포함)
-- 서버 내부 루프로 range 전체를 SADD — 클라이언트 왕복 없이 한 번에 대량 채우기
local from = tonumber(ARGV[1])
local to = tonumber(ARGV[2])
local batch = {}
local count = 0
for i = from, to do
    table.insert(batch, tostring(i))
    if #batch >= 4000 then
        redis.call('SADD', KEYS[1], unpack(batch))
        batch = {}
    end
end
if #batch > 0 then
    redis.call('SADD', KEYS[1], unpack(batch))
end
return redis.call('SCARD', KEYS[1])
