FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr tesseract-ocr-eng tesseract-ocr-hin fonts-dejavu-core curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 harbor \
    && useradd --uid 10001 --gid harbor --create-home harbor \
    && mkdir /data && chown harbor:harbor /data
WORKDIR /app
COPY --from=build /build/target/text-harbor.jar app.jar
ENV SERVER_ADDRESS=0.0.0.0 TEXT_HARBOR_STORAGE=/data OMP_THREAD_LIMIT=1
USER harbor
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/api/capabilities > /dev/null || exit 1
ENTRYPOINT ["java", "-Xmx256m", "-Djava.awt.headless=true", "-jar", "/app/app.jar"]
