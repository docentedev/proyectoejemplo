FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Gradle deposita el JAR compilado en la carpeta 'build/libs/'
COPY build/libs/*.jar app.jar

# Crear un usuario sin privilegios por seguridad en producción
RUN useradd -m springuser && chown -R springuser /app
USER springuser

# Exponer el puerto de producción (80)
EXPOSE 80

ENTRYPOINT ["java", "-jar", "app.jar"]