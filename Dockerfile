FROM maven:3.9.6-eclipse-temurin-21 AS spring-build
WORKDIR /build
COPY pom.xml ./
COPY petroxpert-ms-contract/ petroxpert-ms-contract/
COPY petroxpert-ms-persistence/ petroxpert-ms-persistence/
COPY petroxpert-ms-services/ petroxpert-ms-services/
COPY petroxpert-ms-presentation/ petroxpert-ms-presentation/
COPY petroxpert-ms-application/ petroxpert-ms-application/
RUN mvn -B -ntp package

FROM eclipse-temurin:21-jre-jammy AS java-runtime

# Keep the existing Playwright/Node runtime and the public Fly port.
FROM mcr.microsoft.com/playwright:v1.61.1-noble
WORKDIR /app
ENV NODE_ENV=production JAVA_HOME=/opt/java/openjdk
ENV PATH="/opt/java/openjdk/bin:${PATH}"
ENV MARKET_REFRESH=30s
COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
COPY package.json package-lock.json ./
RUN npm ci
RUN npx playwright install --with-deps chromium
COPY server.js ./
COPY lib/ lib/
COPY scripts/start-services.mjs scripts/start-services.mjs
COPY --from=spring-build /build/petroxpert-ms-application/target/petroxpert-ms-application-1.0.0.jar app.jar
ENV SPRING_JAR=/app/app.jar
EXPOSE 3000
CMD ["npm", "start"]
