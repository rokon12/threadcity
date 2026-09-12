FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -Pproduction dependency:go-offline

COPY src ./src
RUN mvn -B -Pproduction clean package

FROM eclipse-temurin:25-jre

WORKDIR /app
COPY --from=build /workspace/target/threadcity-0.1.0-SNAPSHOT.jar /app/threadcity.jar

USER 10001:10001
ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/threadcity.jar"]
