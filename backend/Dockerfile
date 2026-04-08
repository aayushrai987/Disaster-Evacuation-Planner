# ---- Build stage ----
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /build

# Copy the backend folder contents specifically
COPY backend/pom.xml backend/
COPY backend/src backend/src/

# Build the jar relative to the backend folder
RUN mvn -f backend/pom.xml -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the built jar from the backend's target folder
COPY --from=build /build/backend/target/backend-0.0.1-SNAPSHOT.jar /app/app.jar

# Render will set PORT; default to 8080 locally
ENV PORT=8080

EXPOSE 8080

# Ensure Spring Boot binds to Render's port
ENTRYPOINT ["sh","-c","java -jar /app/app.jar --server.port=${PORT}"]

