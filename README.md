<!-- ===================== HEADER ===================== -->
<div align="center">

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:6DB33F,100:D82C20&height=220&section=header&text=대규모%20트래픽%20선착순%20쿠폰%20발급%20시스템&fontSize=34&fontColor=ffffff&fontAlignY=38&desc=Redis%20Lua%20·%20Kafka%20비동기%20영속화%20·%20자가복구%20·%20동시성%20제한%20대기열&descSize=16&descAlignY=58" width="100%" />

**LG유플러스 유레카 백엔드 개발자(비대면) 종합프로젝트 · 2조 「투게더」**

<br/>

![Java](https://img.shields.io/badge/Java-21-007396?style=for-the-badge&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F?style=for-the-badge&logo=springboot&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?style=for-the-badge&logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7-DC382D?style=for-the-badge&logo=redis&logoColor=white)
![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-3.9-231F20?style=for-the-badge&logo=apachekafka&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?style=for-the-badge&logo=docker&logoColor=white)
![k6](https://img.shields.io/badge/k6-7D64FF?style=for-the-badge&logo=k6&logoColor=white)
![React](https://img.shields.io/badge/React-19-61DAFB?style=for-the-badge&logo=react&logoColor=black)

</div>

<br/>

## 🔖 목차

- [프로젝트 소개]
- [팀 소개]
- [기술 스택]
- [요구사항 요약]
- [ERD]
- [시스템 아키텍처]
- [프로젝트 구조]
- [주요 기능 / API]
- [실행 방법]
- [부하 테스트]
- [정합성 검증]
- [테스트]

<br/>

## 📌 프로젝트 소개

다수 사용자가 동시에 몰리는 선착순 이벤트에서 **재고 초과·부족 발급 0건**과 **1인 1매 제한**을
DB 병목 없이 보장하는 것을 목표로 한 쿠폰 발급 시스템입니다.

| 구분 | 내용 |
|---|---|
| **핵심 목표** | 20,000건 동시 요청에서 오버셀 0건 · 이력-재고 불일치 0건 |
| **접근 방식** | Redis Lua 원자적 처리로 정합성 보장, Kafka로 DB 쓰기 비동기 분리 |
| **안전망** | 재조정 스케줄러 + DLT 재처리 + Redis 전체 유실 시 DB 기준 복구 |
| **유량 제어** | "속도 제한"이 아닌 "동시성 제한" 기반의 이벤트 드리븐 대기열 |
| **성과** | 응답 지연 최댓값 **6.24s → 43.88ms** (2단계 발급 재설계) |

<br/>

## 👥 팀 소개

<table>
<tr>
<td align="center"><a href="https://github.com/bumjpark"><img src="https://github.com/bumjpark.png" width="100px;" alt=""/><br/><sub><b>박종범</b></sub></a><br/><sub>팀장</sub></td>
<td align="center"><a href="https://github.com/YJ720"><img src="https://github.com/YJ720.png" width="100px;" alt=""/><br/><sub><b>이용재</b></sub></a><br/><sub>팀원</sub></td>
<td align="center"><a href="https://github.com/jetleetop"><img src="https://github.com/jetleetop.png" width="100px;" alt=""/><br/><sub><b>이헌진</b></sub></a><br/><sub>팀원</sub></td>
<td align="center"><a href="https://github.com/Jmg9808"><img src="https://github.com/Jmg9808.png" width="100px;" alt=""/><br/><sub><b>정문구</b></sub></a><br/><sub>팀원</sub></td>
<td align="center"><a href="https://github.com/pcy9849-blip"><img src="https://github.com/pcy9849-blip.png" width="100px;" alt=""/><br/><sub><b>박찬영</b></sub></a><br/><sub>팀원</sub></td>
</tr>
</table>



<br/>

## 🛠️ 기술 스택

### Backend
![Java](https://img.shields.io/badge/Java%2021-007396?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot%204.1-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![Spring Data JPA](https://img.shields.io/badge/Spring%20Data%20JPA-6DB33F?style=flat-square&logo=spring&logoColor=white)
![Spring Kafka](https://img.shields.io/badge/Spring%20for%20Kafka-6DB33F?style=flat-square&logo=springboot&logoColor=white)

### Data / Messaging
![MySQL](https://img.shields.io/badge/MySQL%208-4479A1?style=flat-square&logo=mysql&logoColor=white)
![Flyway](https://img.shields.io/badge/Flyway-CC0200?style=flat-square&logo=flyway&logoColor=white)
![Redis](https://img.shields.io/badge/Redis%207-DC382D?style=flat-square&logo=redis&logoColor=white)
![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka%203.9-231F20?style=flat-square&logo=apachekafka&logoColor=white)

### Infra / Test
![Docker](https://img.shields.io/badge/Docker%20Compose-2496ED?style=flat-square&logo=docker&logoColor=white)
![Nginx](https://img.shields.io/badge/Nginx-009639?style=flat-square&logo=nginx&logoColor=white)
![k6](https://img.shields.io/badge/k6-7D64FF?style=flat-square&logo=k6&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white)

### Frontend
![React](https://img.shields.io/badge/React%2019-61DAFB?style=flat-square&logo=react&logoColor=black)
![Vite](https://img.shields.io/badge/Vite%207-646CFF?style=flat-square&logo=vite&logoColor=white)
![Tailwind CSS](https://img.shields.io/badge/Tailwind%20CSS%204-06B6D4?style=flat-square&logo=tailwindcss&logoColor=white)
![React Query](https://img.shields.io/badge/TanStack%20Query%205-FF4154?style=flat-square&logo=reactquery&logoColor=white)

### Collaboration
![GitHub](https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white)
![Jira](https://img.shields.io/badge/Jira-0052CC?style=flat-square&logo=jira&logoColor=white)

<br/>

## 📋 요구사항 요약

<details>
<summary><b>요구사항 상세 펼쳐보기</b></summary>
<div markdown="1">

### 2-1. 기능 요구사항 (FR)
| ID | 분류 | 요구사항 |
|---|---|---|
| FR-1 | 사전 조건 / 데이터 | 가상 회원 정보 기반으로 운영 (회원가입·로그인 기능 미구현) |
| FR-2 | 사전 조건 / 데이터 | 개인정보는 로그·API 응답 등 모든 노출 지점에서 마스킹 처리 |
| FR-3 | 사전 조건 / 데이터 | 쿠폰/캠페인 정보(종류, 할인 정책)는 가상으로 생성, 세부 기획은 팀 자유 |
| FR-4 | 사전 조건 / 데이터 | 가상 유저 100만 명, 발급 이력 300만 건 더미데이터 생성·적재 |
| FR-5 | 사전 조건 / 데이터 | 알림 발송 등 외부 연동은 Mocking 처리 |
| FR-6 | 쿠폰 발급 | 한정 수량(재고 10,000장) 쿠폰을 선착순으로 발급 |
| FR-7 | 쿠폰 발급 | 동시 20,000건 요청이 몰려도 초과 발급 0건 보장 |
| FR-8 | 쿠폰 발급 | 1인 최대 1매로 발급 제한 |
| FR-9 | 쿠폰 발급 | 부하 테스트(k6)로 트래픽 집중 상황을 직접 재현·시연 |
| FR-10 | 쿠폰 발급 | 오픈 시각 예약, 오픈 전 요청에는 대기 상태와 오픈 예정 시각 안내 |
| FR-11 | 쿠폰 발급 | 발급 현황(발급 수량 / 잔여 수량) 실시간 조회 |
| FR-12 | 쿠폰 정합성 검증 | 발급/사용/취소/만료 등 쿠폰 전체 상태 관리 |
| FR-13 | 쿠폰 정합성 검증 | 동일 상태 변경 요청이 반복·동시 발생해도 단 1회만 반영 (멱등성) |
| FR-14 | 쿠폰 정합성 검증 | 이력·재고 불일치를 스스로 검증할 수 있는 수단(검증 쿼리, 검증 배치) 제공 |
| FR-15 | 쿠폰 정합성 검증 | 300만 건 전체 데이터 대상 검증, 재실행 시 항상 동일 결과 |
| FR-16 | 쿠폰 정합성 검증 | 검증 결과를 리포트(CSV 등) 형태로 자동 생성 |

### 2-2. 비기능 요구사항 (NFR)
| ID | 분류 | 요구사항 |
|---|---|---|
| NFR-1 | 정확성/일관성 | 재고 초과 발급 0건, 이력-재고 불일치 0건 — 프로젝트 최우선 지표 |
| NFR-2 | 동시성 | 동일 재고 카운터에 대한 20,000건 동시 요청을 경합 없이 원자적으로 처리 |
| NFR-3 | 응답 지연 최소화 | 발급 응답은 동기로 즉시 처리, 이력 적재는 비동기로 분리 (처리 속도 자체는 평가 대상 아님) |
| NFR-4 | 재현성 | 검증 배치는 결정적(deterministic)이며 부작용 없는 순수 집계로 설계 |
| NFR-5 | 개인정보 보호 | 로그·API 응답 등 모든 출력 경로에서 개인정보 마스킹 |
| NFR-6 | 확장 가능한 상태 설계 | 취소 정책이 세분화되어도(발급취소/사용취소 등) 수용 가능한 구조 |
| NFR-7 | 트래픽 재현 가능성 | 오픈 시각 임박 시점의 트래픽 집중 상황을 재현·측정 가능해야 함 |

### 2-3. 적용 기술 스택 상세
| 영역 | 기술 | 확정 여부 | 비고 |
|---|---|---|---|
| 원자적 재고/중복 처리 | Redis + Lua Script (EVAL) | ✅ 확정 | 재고 체크 + 중복 체크 + 차감·발급을 하나의 스크립트로 원자 실행. 분산락 불필요 |
| 1인 1매 제한 | Redis Set (SADD) | ✅ 확정 | 위 Lua Script와 동일 자료구조 재사용 |
| 오픈 시각 관리 | Redis (cache-aside) | ✅ 확정 | DB가 원본, Redis는 TTL 캐시 |
| 멱등성 처리 (쿠폰 사용/취소) | Redis (Idempotency-Key + TTL) | ✅ 확정 | 클라이언트가 키 생성 후 헤더로 전달, 중복 요청 시 캐시된 응답 반환 |
| 상태 변경 저장소 | RDB, 조건부 UPDATE | ✅ 확정 | 예: `UPDATE coupon SET status='USED' WHERE id=? AND status='ISSUED'` |
| 이벤트 스트리밍 | Kafka | ✅ 확정 | 발급 성공 이벤트 발행 → Consumer가 배치 단위로 DB에 반영 |
| 발급 이력 저장 | MySQL, append-only + UNIQUE 제약 | ✅ 확정 | user_id + coupon_type UNIQUE로 중복 적재 원천 차단 |
| 데이터베이스 | MySQL | ✅ 확정 | |
| API 서버 프레임워크 | Java + Spring Boot | ✅ 확정 | |
| 부하 테스트 도구 | k6 | ✅ 확정 | 평가 대상 아님, 팀 자유 |


</div>
</details>

<br/>

## 🗄️ ERD
<div align="center">
<img width="900" alt="ERD" src="https://github.com/user-attachments/assets/a18d4602-76bd-4731-ac70-70986a33c7c1" />
</div>


| 테이블명 | 설명 |
|---|---|
| **`users`** | 쿠폰을 발급받는 유저 정보 |
| **`coupon_policy`** | 어떤 쿠폰을 얼마나, 언제부터 언제까지 발급할지 정하는 마스터(정책) 테이블 |
| **`coupon_issue`** | 실제로 유저에게 쿠폰이 발급된 기록 테이블 |
| **`coupon_history`** | `coupon_issue`의 상태(발급/사용/취소 등)가 바뀔 때마다 남기는 이력 로그 테이블 |
| **`verification_report`** | 쿠폰이 실제로 맞게 발급됐는지 정합성을 검증한 결과를 남기는 테이블 |
| **`reconciliation_log`** | 시스템 처리 중 실패하거나 문제가 생긴 건들을 재처리하기 위해 기록해두는 테이블 |
| **`queue_join_log`** | 대기열에 진입한 유저들의 상태, 대기 순번(Rank), 진입 시간 등을 기록하는 테이블 |
| **`mock_notification_bulk_job`** | 다수 유저 대상 대량 알림 작업의 전체 진행 상태와 발송 통계(성공/실패)를 관리하는 테이블  |
| **`mock_notification_log`** | 개별 유저에게 발송된 알림 내역과 발송 결과(상태, 실패 사유 등)를 상세히 기록하는 로그 테이블  |
| **`coupon_issue_seq`<br>`coupon_history_seq`** | 대량 데이터 삽입 시 성능 최적화를 위해 발급 및 이력 엔티티의 식별자(ID)를 독립적으로 채번하고 관리하는 시퀀스 테이블 |
<br/>


## 🏗️ 시스템 아키텍처

**Redis Lua 원자적 처리 + 비동기(fire-and-forget) Kafka 발행 + 재조정 스케줄러 + `/recover` + 이벤트 기반 동시성 제한 대기열**

<div align="center">
<img width="900" alt="request-flow" src="https://github.com/user-attachments/assets/e742dae3-8027-405f-a2c7-9707022b56b9" />
</div>


### 📂 프로젝트 구조

## :open_file_folder: 프로젝트 구조

```
├── src/main/java/com/ureca/myureca
│  ├── controller/    REST 엔드포인트
│  ├── service/     발급·대기열·검증·재처리·복구 로직
│  ├── consumer/     Kafka Consumer + DLT 재처리
│  ├── domain/      coupon · queue · reconc
│  │  ├── coupon/         쿠폰 정책·발급·이력
│  │  ├── queue/          대기열
│  │  ├── user/           유저
│  │  ├── verification/   정합성 검증 리포트
│  │  ├── reconciliation/ 재처리 로그
│  │  └── notification/   Mock 알림
│  ├── config/       Redis/Kafka/Async/Scheduling 등 인프라 설정
│  ├── dto/          요청/응답 DTO
│  ├── exception/    커스텀 예외 + GlobalExceptionHandler
│  ├── repository/   Spring Data JPA 리포지토리
│  ├── support/      RedisKeys, KafkaConsumerLagChecker 등 공용 유틸
│  └── util/         MaskingUtils 등
├── src/main/resources/db/migration/  Flyway 마이그레이션 (V1~V7)
├── src/test/java/    단위/통합/동시성 테스트
├── frontend/         React 19 + Vite 프론트엔드
├── loadtest/         k6 부하테스트 스크립트 (burst-20k.js 등)
├── docker-compose.yml               기본 인프라(mysql/redis/kafka/kafka-ui) + app
└── docker-compose.loadtest.yml      k6 부하테스트 오버레이
```


### 요청 흐름
```
[클라이언트]
│ ① POST /api/queue/join (Redis ZSET, userId
│ 서버가 issue() 진입점에서 입장 여부를 직접 재검증 (대기열 우회 차단)
▼
[Redis Lua Script] ── 원자적 1회 실행
├─ 재고 체크 + 중복 체크(ISSUED SET / RESERVED ZSET)
└─ 재고 차감 + RESERVED ZSET 등록
│
├─▶ 즉시 202 ACCEPTED 응답 (동기 대기 없음)
▼
[Kafka Producer] fire-and-forget 발행 (ack 대기 없음)
▼
[Kafka Consumer] poll 배치 단위 JDBC Batch Insert → MySQL 저장
│ 저장 성공 시 RESERVED → ISSUED 확정 (Redis)
▼
[Reconciliation Scheduler] RESERVED에 오래 남은 "미아" 예약을 주기적으로 정리·재발행
[DLT] Consumer 처리 실패 메시지는 Dead Letter Topic으로 이동 후 재처리
[POST /api/coupons/{eventId}/recover] Redis 전체 유실 시 DB 기준으로 재고·이력 재계산
(Kafka Consumer lag이 0이 아니면 부분 복구 방지를 위해 즉시 실패)
```

### 핵심 설계 결정

| # | 결정 | 근거 |
|---|---|---|
| 1 | **오버셀 방지는 Lua 스크립트 하나로** | 재고 확인 + 중복 확인 + 차감을 원자 실행 → 분산락 없이 20,000건 동시 요청에서 오버셀 0건 |
| 2 | **정합성에 동기 대기는 불필요** | `RESERVED → ISSUED` 2단계 + 스케줄러 안전망 → Kafka ack를 기다리지 않고 즉시 응답하면서 메시지 유실도 방지 (지연 6.24s → 43.88ms) |
| 3 | **정합성과 유량 제어는 별개 계층** | Lua는 "무엇이 맞는 답인가"만, 대기열/admission cursor는 "얼마나 흘려보낼 것인가"만 담당 |
| 4 | **"속도 제한"이 아니라 "동시성 제한"** | 초당 N명 대신 "지금 몇 명 처리 중인가"라는 실측값 기준 → 값을 잘못 골라도 구조적으로 한도 초과 불가 |


### 주요 기능 / API

| 도메인 | 엔드포인트 | 설명 |
|---|---|---|
| 대기열 | `POST /api/queue/join`, `GET /api/queue/status` | 입장 순번 발급(멱등) · 입장 여부 폴링 |
| 쿠폰 발급 | `POST /api/coupon-policies/{policyId}/issue` | 선착순 발급 (202 ACCEPTED + 접수증) |
| 발급 접수 조회 | `GET /api/coupons/receipt/{receiptId}` | 비동기 발급 처리 상태 조회 |
| 쿠폰 사용 | `POST /api/coupons/{couponIssueId}/use` | 조건부 UPDATE + Idempotency-Key 멱등 처리 |
| 내 쿠폰 | `GET /api/users/{userId}/coupons` , `.../coupons/{id}/history` | 보유 쿠폰 · 상태 이력 조회 |
| 발급 현황 | `GET /api/coupons/{eventId}/stats` | Redis/DB 재고·발급 수 실시간 비교 |
| 정합성 검증 | `POST /api/admin/verification/run` , `GET /api/admin/verification/reports` | 300만 건 결정적 집계 검증 + CSV 리포트 |
| 재처리 | `POST /api/admin/reconciliation/retry` , `GET .../logs` | 미아 예약·실패 건 재처리 |
| Redis 복구 | `POST /api/coupons/{eventId}/recover` | Redis 유실 시 DB 기준 재구성 |
| 정책 관리 | `/api/admin/coupon-policies` , `/api/admin/queue` | 쿠폰 정책 · 대기열 한도 관리 (admin) |
| 알림 (Mock) | `/api/mock/notifications` | 외부 알림 연동 Mocking |
| 헬스체크 | `GET /api/health` | 인프라 상태 |

<br/>


## 🚀 실행 방법

### 사전 요구사항
- JDK 21
- Docker / Docker Compose

### 1. 환경변수 설정

```bash
cp .env.example .env
# MYSQL_ROOT_PASSWORD, MYSQL_PASSWORD 는 직접 채워주세요. (.env 는 git에 커밋되지 않습니다)
```

### 2-A. 인프라만 컨테이너 + 앱은 로컬 실행 (개발용)

```bash
docker compose up -d mysql redis kafka kafka-ui
./gradlew bootRun
```

### 2-B. 앱까지 전부 컨테이너로

```bash
docker compose up -d --build
```

### 3. 프론트엔드

```bash
cd frontend
npm install
npm run dev
```

| 서비스 | 주소 |
|---|---|
| 애플리케이션 | http://localhost:8080 |
| Kafka UI | http://localhost:8090 |
| 프론트엔드 (dev) | http://localhost:5173 |

<br/>


## 🔥 부하 테스트

`loadtest/burst-20k.js` — 한 VU가 `queue/join → status 폴링 → issue` 전체 흐름을 1회 수행.
재고 10,000장 / 동시 20,000 VU / ramp-up 60s 기준으로 초과 발급 0건을 검증합니다.

세 가지 실행 방식을 지원합니다 (자세한 옵션은 `loadtest/README.md` 참고):

**방법 A — 전부 도커 (권장)**: 앱과 k6를 같은 `coupon-network`에 올려 `k6 → coupon-app:8080`을
직통으로 칩니다. 호스트 `localhost:8080`을 거치면 Docker NAT에서 커넥션 트래킹/임시 포트가
고갈되어 대량 VU에서 `i/o timeout`이 발생하는데, 이 경로를 아예 없앤 방식입니다.

```bash
C=(docker compose -f docker-compose.yml -f docker-compose.loadtest.yml)

"${C[@]}" up -d --build
"${C[@]}" logs -f app                       # healthy 될 때까지

# k6는 profiles:[loadtest] — --profile을 붙여야 뜬다. POLICY_ID 생략 시 재고 10,000 정책 자동 생성
"${C[@]}" --profile loadtest run --rm k6 run /scripts/burst-20k.js

"${C[@]}" down
```

> **메모리 주의**: `ramping-vus target=N`은 VU당 ~1~3MB를 그대로 할당합니다(20,000 VU ≈ 20~60GB).
> Docker Desktop 메모리를 12GB+로 올리고, `coupon-k6`가 `exit 137`이면 OOM — `PEAK`를 낮춰가며
> (`-e PEAK=3000`부터) 한계를 찾으세요.

**방법 B — 앱은 도커, k6는 호스트**:

```bash
docker compose -f docker-compose.yml -f docker-compose.loadtest.yml up -d --build app
ulimit -n 200000
k6 run -e BASE=http://localhost:8080 -e POLICY_ID=1 loadtest/burst-20k.js
```

**방법 C — 앱도 호스트 (개발 중 빠른 반복)**:

```bash
docker compose up -d
./gradlew bootRun
ulimit -n 200000
k6 run -e BASE=http://localhost:8080 loadtest/burst-20k.js
```

# 대기열 폴링·재시도까지 포함한 시나리오
k6 run loadtest/burst-20k-poll-retry.js

<br/>


## ✅ 정합성 검증

- `POST /api/admin/verification/run` → 발급 이력·재고 전량을 **부작용 없는 순수 집계**로 대조
- 재실행 시 항상 동일 결과 (결정적)
- 불일치 건은 `reports/verification-{id}-{timestamp}.csv` 로 자동 저장

<br/>


## 🧪 테스트

```bash
./gradlew test      # 단위 테스트 (기본)
```

> ⚠️ `./gradlew integrationTest` 는 실제 MySQL/Redis/Kafka에 붙어 Flyway 마이그레이션을
> 다시 돌립니다. **대상 DB의 데이터가 삭제될 수 있으니** 날아가도 되는 환경에서만 실행하세요.
> (2026-08-29 개발 DB 유실 사고 이후 기본 실행에서 분리됨)

<br/>



 <img src="https://contrib.rocks/image?repo=bumjpark/Ureca_final_project_02" />
</a>

<div align="center">
<img src="https://capsule-render.vercel.app/api?type=waving&color=0:D82C20,100:6DB33F&height=100&section=footer" width="100%" />
</div>