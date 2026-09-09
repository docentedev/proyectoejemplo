# Integración con DynamoDB

Guía paso a paso para conectar `proyectoejemplo` (Spring Boot en EC2) a **Amazon DynamoDB**, usando el **IAM Role de la instancia EC2** en vez de credenciales estáticas.

## Por qué DynamoDB en vez de Aurora RDS

El proyecto empezó con Aurora RDS (PostgreSQL), pero se migró a DynamoDB porque:

- No hay un cluster que se pueda quedar `Detenido` — DynamoDB no tiene servidor que arrancar/parar.
- No hay usuario/contraseña que gestionar o perder — el acceso se hace vía **IAM Role**.
- No requiere abrir reglas de Security Group para el puerto de base de datos — se accede por HTTPS público.
- Free tier "para siempre" (25 GB + 25 WCU/RCU al mes), no solo los 12 meses del free tier de RDS.
- El modelo de datos (catálogo de productos, sin relaciones complejas) no necesita SQL.

## 1. Crear la tabla en DynamoDB

En la consola de AWS → **DynamoDB** → **Tables** → **Create table**:

| Campo | Valor |
|---|---|
| Nombre de la tabla | `Products` |
| Clave de partición | `id` — tipo **Cadena (String)** |
| Clave de ordenación | (vacío, no se usa) |
| Configuración de la tabla | Predeterminada (**Modo de capacidad: Bajo demanda**) |
| Cifrado | Clave propiedad de AWS (default) |
| Etiquetas | Ninguna |

> El nombre debe coincidir exactamente (distingue mayúsculas/minúsculas) con la variable de entorno `DYNAMO_TABLE_NAME` usada por la app (ver punto 4).

DynamoDB es *schemaless*: no hace falta declarar el resto de los atributos (`name`, `price`, `stock`) al crear la tabla — cada item puede tener sus propios atributos.

## 2. IAM Role para la instancia EC2

La EC2 necesita un IAM Role con permisos sobre la tabla, para que el SDK de AWS pueda leer/escribir sin credenciales estáticas.

1. **IAM → Roles → Create role** (si no existe ya uno).
2. Trusted entity: **AWS service → EC2**.
3. Adjuntar la policy **`AmazonDynamoDBFullAccess`** (lectura y escritura completa). Opcionalmente también `AmazonDynamoDBReadOnlyAccess`, aunque es redundante si ya tienes FullAccess.
4. Nombre del role, por ejemplo: `EC2-DynamoDB-Access-Role`.
5. **EC2 → Instances → selecciona tu instancia → Actions → Security → Modify IAM role** → selecciona el role recién creado (o el existente) → **Update IAM role**.

No se generan ni se copian llaves de acceso (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`) en ningún momento — el SDK las obtiene automáticamente del metadata service de la instancia mientras el role esté asociado.

## 3. Dependencias (`build.gradle`)

Se usa el AWS SDK v2 (Enhanced Client para DynamoDB), gestionado por su propio BOM:

```groovy
ext {
    awssdkVersion = '2.29.52' // verificar última versión en mvnrepository.com/artifact/software.amazon.awssdk/bom
}

dependencyManagement {
    imports {
        mavenBom "software.amazon.awssdk:bom:${awssdkVersion}"
    }
}

dependencies {
    // ...
    implementation 'software.amazon.awssdk:dynamodb-enhanced'
    implementation 'software.amazon.awssdk:dynamodb'
}
```

Ya **no** se usan `spring-boot-starter-data-jpa` ni el driver de PostgreSQL — se quitaron al migrar desde Aurora.

## 4. Configuración (`application.properties`)

```properties
# --- Base de datos (DynamoDB) ---
# Sin credenciales estáticas: se usa el IAM Role de la instancia EC2.
# La región la resuelve el SDK de AWS automáticamente desde la variable de entorno AWS_REGION.
app.dynamo.table-name=${DYNAMO_TABLE_NAME:Products}
```

Variables de entorno que necesita la app en runtime:

| Variable | Valor | ¿Es secreta? |
|---|---|---|
| `AWS_REGION` | `us-east-2` | No |
| `DYNAMO_TABLE_NAME` | `Products` | No |

Ninguna es secreta porque no hay contraseñas ni llaves involucradas — solo son nombres/config.

## 5. Código

### `Product.java` — modelo mapeado a DynamoDB

```java
@DynamoDbBean
public class Product {

    private String id;      // partition key, generado como UUID en el código (no autoincremental como en SQL)
    private String name;
    private Double price;
    private Integer stock;

    // constructor vacío obligatorio para el Enhanced Client, constructor con campos, getters/setters...

    @DynamoDbPartitionKey
    public String getId() { return id; }
    // ...
}
```

### `DynamoDbConfig.java` — beans de Spring

```java
@Configuration
public class DynamoDbConfig {

    @Value("${app.dynamo.table-name}")
    private String tableName;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder().build(); // región + credenciales resueltas automáticamente
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    }

    @Bean
    public DynamoDbTable<Product> productTable(DynamoDbEnhancedClient enhancedClient) {
        return enhancedClient.table(tableName, TableSchema.fromBean(Product.class));
    }
}
```

### `ProductController.java` — CRUD usando el `DynamoDbTable<Product>`

El controller recibe el bean `DynamoDbTable<Product>` por inyección de dependencias y opera directo sobre la tabla (`putItem`, `getItem`, `scan`, `deleteItem`) en vez de un repositorio JPA. Los métodos quedan protegidos con `@PreAuthorize` según el rol de Cognito:

| Método HTTP | Roles permitidos |
|---|---|
| `GET /api/products`, `GET /api/products/{id}` | Cualquier usuario autenticado |
| `POST /api/products` | `Admin`, `Colaborador` |
| `PUT /api/products/{id}` | `Admin` |
| `PATCH /api/products/{id}` | `Admin` |
| `DELETE /api/products/{id}` | `Admin` |

## 6. Pipeline de deploy (`.github/workflows/deploy.yml`)

En el paso de `docker run` sobre la EC2, se agregan las dos variables (no secretas):

```yaml
-e AWS_REGION='us-east-2' \
-e DYNAMO_TABLE_NAME='Products' \
```

No hace falta agregar ningún secret nuevo en GitHub — a diferencia de `API_GATEWAY_SECRET`, aquí no hay nada sensible que ocultar.

## 7. Verificación

1. Confirma que la tabla `Products` existe y está `Active` en la consola de DynamoDB.
2. Confirma que la EC2 tiene el IAM Role asociado (**EC2 → Instances → tu instancia → pestaña Security → IAM Role**).
3. Haz el deploy (push a `main`, o re-ejecuta el workflow).
4. En los logs de Docker (`docker logs <container>`), al arrancar debería sembrar 3 productos de ejemplo (`seedIfEmpty()`) si la tabla estaba vacía.
5. Probar los endpoints:
   ```bash
   curl -H "Authorization: Bearer <JWT>" -H "X-Secret-Gateway: <secreto>" https://.../api/products
   ```

## Troubleshooting

- **`software.amazon.awssdk:bom:X.Y.Z not found`**: la versión fijada en `build.gradle` puede estar desactualizada; revisa la última en [mvnrepository.com](https://mvnrepository.com/artifact/software.amazon.awssdk/bom).
- **`AccessDeniedException` al leer/escribir la tabla**: el IAM Role no está asociado a la instancia, o la policy no incluye permisos sobre esa tabla — revisa el paso 2.
- **La tabla no aparece / `ResourceNotFoundException`**: el nombre en `DYNAMO_TABLE_NAME` no coincide exactamente (mayúsculas/minúsculas) con el nombre real de la tabla, o la región (`AWS_REGION`) no es la misma donde se creó la tabla.
