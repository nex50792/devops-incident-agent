# multi-stage build で、最終イメージは JRE + jar のみに絞る (~ 250 MB)
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

# 依存解決を先にキャッシュ (gradle wrapper + build script を先にコピー)
COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# ソースを後からコピー (ソース変更時の rebuild 範囲を最小化)
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# --- runtime stage ---
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# 非 root ユーザで起動 (security best practice)
RUN groupadd --system app && useradd --system --gid app --no-create-home app
USER app

COPY --from=build --chown=app:app /app/build/libs/*.jar app.jar

EXPOSE 8081

# Spring Boot actuator の health endpoint で healthcheck
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"UP"' || exit 1

# JVM tuning: container メモリ制限を尊重 (-XX:MaxRAMPercentage), G1 GC (デフォルト Java 21+)
ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseStringDeduplication", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/app.jar"]
