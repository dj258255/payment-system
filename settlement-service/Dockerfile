# settlement-service 컨테이너 이미지 — 로컬 kind용 최소 구성.
# 빌드: ./gradlew -p settlement-service bootJar && docker build -t settlement-service:local settlement-service
FROM eclipse-temurin:21-jre
COPY build/libs/pay-settlement-service-0.0.1-SNAPSHOT.jar /app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
