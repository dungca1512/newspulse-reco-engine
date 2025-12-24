FROM sbtscala/scala-sbt:eclipse-temurin-17.0.4_1.7.1_3.2.0 AS build
WORKDIR /app
COPY build.sbt .
COPY project ./project
RUN sbt update
COPY src ./src
RUN sbt assembly

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/scala-2.13/trending-engine-scala-assembly-*.jar app.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]