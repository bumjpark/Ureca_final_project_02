-- 소유권을 확인한 뒤에만 TTL을 연장한다(watchdog 하트비트용).
-- 이미 TTL이 만료돼 다른 프로세스가 같은 키로 새 락을 잡았다면 토큰이 달라지므로
-- 여기서 걸러지고, 그 경우 남의 락 TTL을 잘못 늘려버리는 사고가 생기지 않는다.
--
-- KEYS[1] = 락 키
-- ARGV[1] = 락을 잡을 때 넣어둔 내 토큰
-- ARGV[2] = 연장할 TTL(ms)
--
-- return: 1(연장 성공) / 0(내가 주인이 아니라서 연장 안 함 — 락을 이미 잃었다는 뜻)

if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('PEXPIRE', KEYS[1], ARGV[2])
else
    return 0
end
