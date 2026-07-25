# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# libwebp-tools provides `dwebp` (transcode WEBP menu photos, OpenPDF can't decode
# WEBP) and ghostscript provides `gs` (convert the print-ready menu PDF to CMYK).
RUN apk add --no-cache libwebp-tools ghostscript
RUN addgroup -S app && adduser -S app -G app && mkdir -p /data/uploads && chown -R app:app /data/uploads
USER app
COPY --from=build /app/target/cashflow-api-*.jar /app/app.jar
EXPOSE 3001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
