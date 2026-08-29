# ── build ───────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 의존성 레이어 캐시
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies -q || true

COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test \
 && cp "$(ls build/libs/*.jar | grep -v plain | head -n 1)" /app.jar

# ── runtime ─────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app.jar app.jar

# 부하테스트 중 컨테이너 메모리 한도에 맞춰 힙 자동 조정
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"
EXPOSE 8080

# reports/ (정합성 검증 CSV) 는 컨테이너 안에서 생성됨
RUN mkdir -p /app/reports

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
