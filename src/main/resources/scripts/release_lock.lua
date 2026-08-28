-- 소유권을 확인한 뒤에만 락을 지운다(compare-and-delete).
-- TTL이 이미 만료돼서 다른 프로세스가 새로 잡은 락을, 뒤늦게 끝난 이전 요청이
-- 무조건 DELETE 해버리는 사고를 막기 위함이다.
--
-- KEYS[1] = 락 키
-- ARGV[1] = 락을 잡을 때 넣어둔 내 토큰
--
-- return: 1(내 락을 지움) / 0(이미 만료돼 남의 락이 되어 있어서 지우지 않음)

if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
