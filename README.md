# Ureca_final_project_02

대규모 트래픽 선착순 쿠폰 발급 시스템 (MySQL, Redis, Kafka)

## 로컬 개발 환경 세팅 (팀원 온보딩)

DB/Redis/Kafka는 각자 로컬 docker로 띄웁니다. (중앙 서버 없이 각자 컴퓨터에서 독립적으로 개발)
스키마는 JPA `ddl-auto: update`로 각자 엔티티 기준으로 자동 생성됩니다.

```bash
# 1. 환경변수 파일 생성 후 값 채우기
cp .env.example .env
#   MYSQL_ROOT_PASSWORD, MYSQL_PASSWORD 는 원하는 값으로 직접 채워주세요.

# 2. 인프라(MySQL/Redis/Kafka) 기동
docker compose up -d

# 3. 애플리케이션 실행
./gradlew bootRun
```

- Kafka UI: http://localhost:8090

