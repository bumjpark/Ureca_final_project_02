-- KEYS[1] = active_token:{token}
-- ARGV[1] = expectedUserId (string)
-- 반환값:
--   1  = 성공 (토큰 소비 완료)
--   0  = 토큰 없음 (만료 또는 이미 소비됨)
--  -1  = userId 불일치 (토큰 도용 시도)

local stored = redis.call('GET', KEYS[1])

-- 토큰이 존재하지 않음
if stored == false then
    return 0
end

-- 토큰의 소유자(userId)가 요청자와 다름
if stored ~= ARGV[1] then
    return -1
end

-- GET → DEL 사이 경쟁 없이 원자적 소비
redis.call('DEL', KEYS[1])
return 1
