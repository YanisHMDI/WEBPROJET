# syntax=docker/dockerfile:1
# Image générique : docker build --build-arg MODULE=monitor-service -t philo/monitor-service .
# 1re étape : compilation avec Maven (dépendances mises en cache tant que les pom.xml ne changent pas)
FROM maven:3.9-eclipse-temurin-17 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/
COPY monitor-service/pom.xml monitor-service/
COPY supervisor-service/pom.xml supervisor-service/
COPY philosopher-service/pom.xml philosopher-service/
# le dépôt Maven est gardé en cache entre deux constructions, et Maven réessaie si Maven Central limite le débit
ARG MAVEN_OPTS_RETRY="-Daether.connector.http.retryHandler.count=6 -Daether.connector.http.retryHandler.interval=5000"
RUN --mount=type=cache,target=/root/.m2 mvn -B -q ${MAVEN_OPTS_RETRY} -pl ${MODULE} -am dependency:go-offline || true
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q ${MAVEN_OPTS_RETRY} -pl ${MODULE} -am package -DskipTests

# 2e étape : image d'exécution légère (JRE seul)
FROM eclipse-temurin:17-jre
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}.jar app.jar
# pas de droits administrateur dans le conteneur
RUN useradd --system --uid 10001 philo
USER philo
# la JVM s'adapte à la limite mémoire du conteneur (Kubernetes : resources.limits.memory)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
