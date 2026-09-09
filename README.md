# Despliegue Automatizado e Idempotente de Spring Boot en AWS EC2 via GHCR

Este proyecto contiene una configuración mínima, limpia y de nivel profesional para empaquetar una aplicación **Spring Boot (Java 21)** con **Gradle**, publicarla en **GitHub Container Registry (GHCR)** y desplegarla de manera totalmente automática e **idempotente** en una instancia **AWS EC2 (t3.micro)** utilizando **GitHub Actions**.

La arquitectura está optimizada bajo la premisa de **DevSecOps**: el repositorio es 100% público, pero la topología de red, las llaves de acceso y las credenciales de infraestructura están completamente blindadas y parametrizadas.

---

## 🚀 Arquitectura y Beneficios para el Entorno

- **Construcción Soberana (Sin Docker Hub):** El JAR se compila en los runners de GitHub y la imagen se almacena gratis en GHCR (`ghcr.io`), evitando las restricciones de descargas (*Rate Limits*) de la capa gratuita de Docker Hub.
- **Idempotencia Total (Aprovisionamiento Autónomo):** Si el servidor EC2 está completamente vacío, el script detectará la falta de herramientas, creará memoria virtual, instalará Docker, refrescará permisos y levantará la app. En despliegues sucesivos, ignorará la instalación y actualizará el contenedor sin romper nada.
- **Optimización de Recursos (Capa Gratuita de AWS):** Diseñado específicamente para instancias `t3.micro` (1 GB RAM). El script aprovisiona automáticamente **2 GB de Swap** para evitar que la máquina se congele debido a la falta de memoria (*Out of Memory*) durante tareas de infraestructura.
- **Mapeo Cruzado de Puertos:** Spring Boot se ejecuta de manera nativa y aislada internamente en el puerto `8080`, pero Docker lo expone hacia internet en el puerto web estándar `80`.

---

## 🛠️ Archivos de Configuración del Proyecto

### 1. Dockerfile
Ubicado en la raíz del proyecto (`/Dockerfile`). Utiliza una imagen ligera de Java 21 y aplica buenas prácticas de seguridad corriendo la app bajo un usuario sin privilegios (`springuser`).

```dockerfile
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Gradle deposita el JAR compilado en build/libs/
COPY build/libs/*.jar app.jar

# Crear un usuario sin privilegios por seguridad en producción
RUN useradd -m springuser && chown -R springuser /app
USER springuser

# Exponer el puerto de producción web estándar (interno)
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 2. Flujo de GitHub Actions
Ubicado de forma obligatoria en `.github/workflows/deploy.yml`.

```yaml
name: Deploy Spring Boot to EC2 via GHCR

on:
  push:
    branches:
      - main  # Se ejecuta automáticamente al hacer push a la rama main

env:
  REGISTRY: ghcr.io
  IMAGE_NAME: ${{ github.repository }} 
  CONTAINER_NAME: spring-app-ejemplo

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write # Permiso obligatorio para escribir en GitHub Packages

    steps:
    - name: Checkout code
      uses: actions/checkout@v4

    # 1. Configurar JDK 21 y la caché automática para Gradle
    - name: Set up JDK 21
      uses: actions/setup-java@v5
      with:
        distribution: 'temurin'
        java-version: '21'
        cache: 'gradle'

    # 2. Dar permisos de ejecución al Gradle Wrapper y compilar el JAR
    - name: Build with Gradle
      run: |
        chmod +x gradlew
        ./gradlew bootJar -x test

    # 3. Autenticarse en GitHub Container Registry (GHCR) usando las variables automáticas
    - name: Log in to GitHub Container Registry
      uses: docker/login-action@v3
      with:
        registry: ${{ env.REGISTRY }}
        username: ${{ github.actor }}
        password: ${{ secrets.GITHUB_TOKEN }}

    # 4. Construir y subir la imagen Docker a GHCR con el tag 'latest'
    - name: Build and push Docker image
      uses: docker/build-push-action@v5
      with:
        context: .
        push: true
        tags: ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:latest

    # 5. Despliegue Automatizado, Aprovisionamiento e Instalación Idempotente vía SSH
    - name: Deploy to EC2 Instance
      uses: appleboy/ssh-action@v1.0.3
      with:
        host: ${{ vars.TARGET_HOST_IP }}  # Lee la IP desde Repository Variables
        username: ec2-user                 # Usuario nativo para Amazon Linux
        key: ${{ secrets.EC2_SSH_KEY }}     # Lee la llave desde Repository Secrets
        port: 22
        envs: TARGET_HOST_IP
        script: |
          # --- APROVISIONAMIENTO IDEMPOTENTE DE SWAP (Evita caídas por falta de RAM) ---
          if [ ! -f /swapfile ]; then
            echo "Instancia sin Swap. Creando 2GB de memoria virtual..."
            sudo dd if=/dev/zero of=/swapfile bs=1M count=2048
            sudo chmod 600 /swapfile
            sudo mkswap /swapfile
            sudo swapon /swapfile
            echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
          fi

          # --- APROVISIONAMIENTO IDEMPOTENTE DE DOCKER ---
          if ! command -v docker &> /dev/null; then
            echo "Docker no está instalado. Iniciando instalación..."
            sudo dnf update -y
            sudo dnf install -y docker
            sudo systemctl start docker
            sudo systemctl enable docker
            sudo usermod -aG docker ec2-user
            echo "Docker instalado con éxito."
          fi

          # --- EJECUCIÓN SEGURA CON PERMISOS REFRESCADOS ---
          # Envolvemos el despliegue dentro del grupo 'docker' para aplicar los privilegios inmediatamente
          sg docker -c "
            # Autenticar el Docker local del EC2 en GHCR
            echo '${{ secrets.GH_CR_PAT }}' | docker login ${{ env.REGISTRY }} -u ${{ github.actor }} --password-stdin

            # Descargar la última versión de la imagen recién subida
            docker pull ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:latest

            # --- PASOS IDEMPOTENTES DEL CONTENEDOR ---
            docker stop ${{ env.CONTAINER_NAME }} || true
            docker rm ${{ env.CONTAINER_NAME }} || true
            docker image prune -f

            # --- MAPEO CRUZADO: Puerto 80 del Servidor al 8080 del Contenedor ---
            docker run -d \
              --name ${{ env.CONTAINER_NAME }} \
              -p 80:8080 \
              -e PORT=8080 \
              -e PUBLIC_HOST_URL='http://$TARGET_HOST_IP' \
              --restart unless-stopped \
              ${{ env.REGISTRY }}/${{ env.IMAGE_NAME }}:latest
          "
      env:
        TARGET_HOST_IP: ${{ vars.TARGET_HOST_IP }}
```

### 3. Propiedades de Entorno (`src/main/resources/application.properties`)
Configuración desacoplada lista para inyecciones en caliente:

```properties
# Escucha el puerto inyectado por Docker (8080), por defecto usa el estándar de desarrollo local
server.port=${PORT:8080}

# Host parametrizado dinámicamente como variable de entorno
app.public-host=${PUBLIC_HOST_URL:http://localhost:8080}
```

---

## ⚙️ Configuración Obligatoria en GitHub

Para que el pipeline funcione de manera segura en un repositorio público, ingresa a tu repositorio de GitHub y dirígete a **Settings > Secrets and variables > Actions**:

### 1. Pestaña de Variables (`Repository Variables`)
Crea una variable no encriptada para el host:
- **`TARGET_HOST_IP`**: La dirección IP pública de tu instancia de AWS EC2 (ej. `18.216.244.5`).

### 2. Pestaña de Secretos (`Repository Secrets`)
Crea los dos secretos de seguridad:
- **`EC2_SSH_KEY`**: El contenido completo de tu archivo de certificado `.pem` (incluyendo las líneas de cabecera `-----BEGIN ...` y cierre `-----END ...`).
- **`GH_CR_PAT`**: Un *Personal Access Token (Classic)* generado desde tu perfil de GitHub (*Settings > Developer Settings > Personal Access Tokens*). Debe contar con los permisos seleccionados **`write:packages`** y **`read:packages`**.

---

## 🔒 Configuración de Red en AWS (Security Group)

Asegúrate de que el firewall de tu instancia en la consola de AWS permita el tráfico web entrante. En las reglas de entrada (**Inbound Rules**) de tu **Security Group**, debes tener habilitados los siguientes accesos:

| Puerto | Protocolo | Origen | Descripción |
| :--- | :--- | :--- | :--- |
| **`22`** | TCP | `0.0.0.0/0` | Acceso SSH (Requerido por GitHub Actions) |
| **`80`** | TCP | `0.0.0.0/0` | Acceso HTTP Público (Requerido para consumir la API) |

---

## 🧪 Verificación y Pruebas del Servicio

Una vez que el pipeline complete su ejecución de forma exitosa, puedes verificar el estado de salud de la aplicación externamente a través de la terminal o navegador usando la URL del endpoint público.

### Prueba Externa con `curl` (Desde tu máquina local):
```bash
curl -i http://<publicip>:80/api/health
```

### Respuesta Esperada (HTTP 200 OK):
```text
HTTP/1.1 200 
Content-Type: application/json
Transfer-Encoding: chunked

{"status":"UP","error":null}
```

## Configurar Seguridad