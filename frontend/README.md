# myureca frontend

선착순 쿠폰 발급 시스템 **myureca**의 시연용 프론트엔드.
일반 서비스가 아니라, 백엔드의 **동시성 제어 · 데이터 정합성**을 평가자에게 보여주는 것이 목적이다.
화면마다 어떤 백엔드 과업의 증거인지 하단 캡션에 명시했다.

## 스택

- React 19 + Vite 7
- Tailwind CSS 4 (`@tailwindcss/vite`)
- TanStack Query 5 (폴링)
- Recharts 2 (차트)
- Pretendard (CDN), 숫자는 `tabular-nums`

## 실행

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173  (/api → localhost:8080 프록시)
```

백엔드 포트가 다르면:

```bash
VITE_API_TARGET=http://localhost:9000 npm run dev
```

빌드: `npm run build` → `dist/`

## 화면

| 경로 | 목적 | 주요 API |
|---|---|---|
| `/event` | 선착순 발급 시연 (202 Accepted 비동기 흐름을 버튼 상태 머신으로) | `POST /api/queue/join`, `GET /api/queue/status`, `POST /api/coupon-policies/{id}/issue`, `GET /api/coupon-policies/{id}/status` |
| `/my-coupons` | 상태 관리 + 개인정보 마스킹 + 멱등성 시연 | `GET /api/users/{id}/coupons`, `GET /api/coupons/{id}/history`, `POST /api/coupons/{id}/use` (Idempotency-Key) |
| `/admin/dashboard` | 부하테스트(k6 20,000 VU) 시연용 관제 | `GET /api/coupon-policies/{id}/status` (1s), `GET /api/admin/verification/reports`, `GET /api/admin/reconciliation/logs`, `GET /api/health?deep=true` |
| `/admin/verification` | 300만 건 정합성 검증 리포트 + 재현성 | `POST /api/admin/verification/run`, `GET /api/admin/verification/reports`, `GET .../reports/{id}?format=csv` |

## 시연 컨텍스트

로그인 기능이 없으므로(FR-1) 상단 바에서 `userId` / `쿠폰 정책`을 직접 고른다. `localStorage`에 저장된다.

## 백엔드가 노출하지 않아 "미집계"로 표시하는 지표

애플리케이션 메트릭(actuator/micrometer)이 없어 아래는 전용 API가 없다. 대시보드에 "백엔드 미집계"로 명시했다.

- 초당 요청 수(RPS)
- 중복 차단 누적 건수 (409는 반환하지만 카운터 없음)
- 재고 소진 거절 누적 건수 (400은 반환하지만 카운터 없음)

대신 `coupon-status` 폴링의 증가분으로 **초당 발급 확정 수(Consumer 처리량)** 와 **누적 발급 확정 수**를 그린다.
Kafka 처리 현황은 `reconciliation_log` 집계(DLT 재처리 / 발행 실패 재발행 / 대기·실패)로 표시한다.

## 쿠폰 상태 모델

백엔드 실제 모델은 `ISSUED / USED / EXPIRED` 3종뿐이다. 별도 `CANCELLED` 상태는 없고,
사용 취소는 `USED → ISSUED` 복귀로 처리된다. 뱃지·타임라인 모두 이 모델을 따른다.
