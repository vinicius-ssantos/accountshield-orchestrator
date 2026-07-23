FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress dependency:go-offline
COPY src src
RUN ./mvnw --batch-mode --no-transfer-progress package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd -r accountshield && useradd -r -g accountshield accountshield
COPY --from=build /workspace/target/*.jar app.jar
RUN chown -R accountshield:accountshield /app
USER accountshield
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
