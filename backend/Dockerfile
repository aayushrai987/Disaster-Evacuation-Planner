# ---- Build stage ----
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /build
COPY . .
RUN mvn -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built jar
COPY --from=build /build/target/backend-0.0.1-SNAPSHOT.jar /app/app.jar

# Render will set PORT; default to 8080 locally
ENV PORT=8080

EXPOSE 8080

# Ensure Spring Boot binds to Render's port
ENTRYPOINT ["sh","-c","java -jar /app/app.jar --server.port=${PORT}"]
