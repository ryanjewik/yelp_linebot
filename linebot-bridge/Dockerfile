# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Copy pom.xml and download dependencies (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage with Node.js for MCP support
FROM eclipse-temurin:17-jre
WORKDIR /app

# Install Node.js 20
RUN apt-get update && apt-get install -y curl && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Copy the built jar from build stage
COPY --from=build /app/target/linebot-bridge-*.jar app.jar

# Expose the port
EXPOSE 8000

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
