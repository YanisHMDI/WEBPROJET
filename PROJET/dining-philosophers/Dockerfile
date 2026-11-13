# Image générique : docker build --build-arg MODULE=monitor-service -t philo/monitor .
FROM maven:3.9-eclipse-temurin-17 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/
COPY monitor-service/pom.xml monitor-service/
COPY supervisor-service/pom.xml supervisor-service/
COPY philosopher-service/pom.xml philosopher-service/
RUN mvn -B -q -pl ${MODULE} -am dependency:go-offline || true
COPY . .
RUN mvn -B -q -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:17-jre
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}.jar app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
