# pay-core 컨테이너 이미지 — 로컬 kind용 최소 구성.
# 빌드: ./gradlew bootJar && docker build -t pay-core:local .
# (운영이라면 레이어드 JAR + 멀티스테이지로 캐시 효율을 올린다)
FROM eclipse-temurin:21-jre
COPY build/libs/pay-0.0.1-SNAPSHOT.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
