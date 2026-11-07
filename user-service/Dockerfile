FROM eclipse-temurin:27-jdk AS build
WORKDIR /workspace
COPY pom.xml ./
COPY user-service/pom.xml user-service/
COPY user-service/src user-service/src
RUN --mount=type=cache,target=/root/.m2 \
    apt-get update && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/* \
    && mvn -B -ntp -f user-service/pom.xml -DskipTests package

FROM eclipse-temurin:27-jre
WORKDIR /app
COPY --from=build /workspace/user-service/target/user-service.jar app.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
