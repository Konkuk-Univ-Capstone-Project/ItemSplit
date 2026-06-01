FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew

COPY src ./src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S itemsplit && adduser -S itemsplit -G itemsplit

COPY --from=build /workspace/build/libs/*.jar app.jar
RUN mkdir -p /app/storage && chown -R itemsplit:itemsplit /app

USER itemsplit
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
