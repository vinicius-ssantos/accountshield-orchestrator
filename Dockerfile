FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY mvnw .
COPY .mvn .mvn
RUN chmod +x mvnw
# accountshield-sdk is a standalone Maven project this build's test scope depends on (issue #55,
# ADR 0037: SdkContractVerificationTest) -- it must be installed into this build stage's local
# repo before dependency:go-offline/package can resolve it, since this image build has no access
# to the host's local Maven repository.
COPY sdk sdk
RUN ./mvnw -f sdk/pom.xml --batch-mode --no-transfer-progress install -DskipTests
COPY pom.xml .
RUN ./mvnw --batch-mode --no-transfer-progress dependency:go-offline
COPY src src
RUN ./mvnw --batch-mode --no-transfer-progress package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd -r accountshield && useradd -r -g accountshield accountshield
COPY --from=build /workspace/target/*.jar app.jar
RUN chown -R accountshield:accountshield /app
USER accountshield
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
