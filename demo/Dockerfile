FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY mvnw .
COPY .mvn .mvn
RUN chmod +x mvnw
# Reuses the root project's own Maven Wrapper against sdk/'s and demo/'s standalone pom.xml files
# (via -f) instead of a Maven-bundled base image, since accountshield-sdk and accountshield-demo
# are independent Maven projects with no reactor relationship to the root pom.xml (ADR 0037).
COPY sdk sdk
RUN ./mvnw -f sdk/pom.xml --batch-mode --no-transfer-progress install -DskipTests
COPY demo demo
RUN ./mvnw -f demo/pom.xml --batch-mode --no-transfer-progress package

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd -r accountshield-demo && useradd -r -g accountshield-demo accountshield-demo
COPY --from=build /workspace/demo/target/accountshield-demo.jar app.jar
RUN chown -R accountshield-demo:accountshield-demo /app
USER accountshield-demo
ENTRYPOINT ["java", "-jar", "app.jar"]
