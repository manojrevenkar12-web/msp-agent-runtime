# Build the console and the service, then run the jar on a small JRE image.
FROM node:22 AS console
WORKDIR /src/console
COPY console/package*.json ./
RUN npm ci
COPY console/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
COPY --from=console /src/console/dist ./console/dist
RUN ./gradlew :service:bootJar --no-daemon -q

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
USER app
COPY --from=build /src/service/build/libs/msp-agent-runtime.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
