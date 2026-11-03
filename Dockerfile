FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml .

RUN mvn dependency:go-offline -B

COPY src ./src

RUN mvn clean package -B


FROM mcr.microsoft.com/playwright/java:v1.64.0-noble

RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr tesseract-ocr-fra tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/*
	
RUN mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install-deps" || true

RUN useradd --create-home --shell /bin/bash webcal

WORKDIR /opt/hers.be-utils

COPY --from=build /build/target/hers.be-utils.jar hers.be-utils.jar

RUN chown -R webcal:webcal /opt/hers.be-utils
USER webcal

EXPOSE 8080

CMD java \
    -Djava.security.egd=file:/dev/./urandom \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --enable-native-access=ALL-UNNAMED \
    -Xms512m \
    -Xmx2g \
    -jar hers.be-utils.jar
