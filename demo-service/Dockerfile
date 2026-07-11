# multi-stage build。Cloud Run 用に PORT 環境変数を尊重する ENTRYPOINT にする。
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# --- runtime stage ---
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app
USER app

COPY --from=build --chown=app:app /app/build/libs/*.jar app.jar

# Cloud Run は PORT env で listen 先を指定する
ENV PORT=8082
EXPOSE 8082

ENTRYPOINT ["java", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+UseStringDeduplication", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "/app/app.jar"]
