# 부하테스트 (k6)

선착순 쿠폰 발급 — 재고 10,000장 / 동시 20,000 VU / ramp-up 60s → **초과 발급 0건** 검증.

`burst-20k.js` 한 VU = `queue/join → status 폴링 → issue` 전체 흐름 1회.

---

## 방법 A — 전부 도커 (권장)

앱 + k6 를 같은 `coupon-network` 에 올려서 `k6 → coupon-app:8080` 을 **직통**으로 친다.
호스트 `localhost:8080` 을 거치면 Docker NAT(포트포워딩)에서 conntrack / ephemeral-port 가
고갈되어 대량 VU 에서 `i/o timeout` 이 발생한다 — 이 경로를 통째로 없앤 것이 핵심.

```bash
# 편의상
C=(docker compose -f docker-compose.yml -f docker-compose.loadtest.yml)

# 1. 인프라(mysql/redis/kafka) + 앱 컨테이너 기동
"${C[@]}" up -d --build
"${C[@]}" logs -f app        # healthy 될 때까지

# 2. k6 실행 — profiles:[loadtest] 라 --profile 을 붙여야 뜬다
#    POLICY_ID 생략 시 재고 10,000 정책을 자동 생성
"${C[@]}" --profile loadtest run --rm k6 run /scripts/burst-20k.js

#    옵션
"${C[@]}" --profile loadtest run --rm \
  -e POLICY_ID=1 -e PEAK=20000 -e RAMP=60s -e HOLD=60s k6 run /scripts/burst-20k.js

# 3. 정리
"${C[@]}" down
```

`BASE_URL` 은 compose 가 `http://coupon-app:8080` 으로 주입한다 (덮어쓸 필요 없음).

> **메모리**: `ramping-vus target=N` 은 반복이 빨라도 VU 고루틴 N 개를 그대로 할당한다.
> 이 스크립트 기준 VU당 ~1~3MB → 20,000 VU = 20~60GB. Docker Desktop → Settings →
> Resources 에서 메모리를 최대한(12GB+) 올리고, `--compatibility-mode=base` 로 VU당
> 메모리를 더 줄인다. `coupon-k6` 가 `exit 137` 이면 OOM → `PEAK` 를 낮춰 이 머신의 한계를 찾는다.
> 우선 `-e PEAK=3000` 으로 감을 잡을 것.

```bash
"${C[@]}" --profile loadtest run --rm \
  -e PEAK=20000 -e RAMP=60s -e HOLD=20s \
  k6 run --compatibility-mode=base /scripts/burst-20k.js
```

---

## 방법 B — 앱은 도커, k6 는 호스트

k6 가 호스트 메모리를 전부 쓸 수 있어 20k VU 여유가 있다.

```bash
# 앱만 컨테이너로
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up -d --build app

# k6 는 호스트에서 published 포트(8080)로
ulimit -n 200000
k6 run -e BASE=http://localhost:8080 -e POLICY_ID=1 loadtest/burst-20k.js
```

호스트에 k6 설치: `brew install k6`

---

## 방법 C — 앱도 호스트 (개발 중 빠른 반복)

```bash
docker compose up -d                       # 인프라만
./gradlew bootRun                          # 앱
ulimit -n 200000
k6 run -e BASE=http://localhost:8080 loadtest/burst-20k.js
```

---

## 옵션

| env | 기본 | 설명 |
|---|---|---|
| `BASE` | `http://app:8080` | 대상 서버 (호스트 실행 시 `http://localhost:8080`) |
| `POLICY_ID` | (없음) | 대상 쿠폰 정책. 생략하면 setup 이 재고 `STOCK` 짜리를 새로 만들고 오픈까지 대기 |
| `STOCK` | `10000` | 자동 생성 시 재고 |
| `PEAK` | `20000` | 최대 동시 VU |
| `RAMP` / `HOLD` | `60s` / `60s` | ramp-up / 피크 유지 |
| `MODE` | `vus` | `vus`(ramping-vus) 또는 `arrival`(ramping-arrival-rate, 순간 폭주 재현) |

---

## 결과 읽기

종료 요약의 커스텀 메트릭:

| 메트릭 | 의미 |
|---|---|
| `r_issued_202` | 발급 접수 성공 — **`count <= STOCK` threshold ✓ 이면 초과 접수 방어 성공** |
| `r_duplicated_409` | 1인 1매 중복 차단 |
| `r_soldout` | 재고 소진 거절 |
| `r_queue_full_503` | 대기열 포화 (max 30,000) |
| `r_rate_limited_429` | 유저별 초당 요청 제한 |
| `t_admit_wait_ms` | join → 대기열 입장까지 걸린 시간 |

**최종 "초과 발급 0건 / 불일치 0건" 확인은** 프론트 `/admin/verification` 에서 해당 정책 검증 실행,
또는:

```bash
curl -s -XPOST "http://localhost:8080/api/admin/verification/run?policyId=<id>&force=true"
curl -s "http://localhost:8080/api/admin/verification/reports?policyId=<id>&size=1"
```

## 실시간으로 보면서

k6 실행 중 브라우저 `/admin/dashboard` 에서 그 정책을 선택해두면 "누적 발급 확정"
라인이 정확히 재고에서 수평이 되고 "초과 발급" 카드가 0 을 유지하는 장면을 볼 수 있다.

## 대기열 통과 속도 조절

기본 300건/s (대기열 5,000명↑ 자동 스케일 시 최대 2,000건/s). 더 빠르게:

```bash
curl -s -XPATCH http://localhost:8080/api/admin/queue/limit \
  -H 'Content-Type: application/json' -d '{"policyId":<id>,"limit":5000}'
```
