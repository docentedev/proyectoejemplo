# Integración con DynamoDB

Guía paso a paso para conectar `proyectoejemplo` (Spring Boot en EC2) a **Amazon DynamoDB**, usando el **IAM Role de la instancia EC2** en vez de credenciales estáticas. Pensada para estudiantes que nunca trabajaron con una base de datos NoSQL — además del "cómo", explica el "por qué" de cada pieza.

## Por qué DynamoDB en vez de Aurora RDS

El proyecto empezó con Aurora RDS (PostgreSQL), pero se migró a DynamoDB porque:

- No hay un cluster que se pueda quedar `Detenido` — DynamoDB no tiene servidor que arrancar/parar.
- No hay usuario/contraseña que gestionar o perder — el acceso se hace vía **IAM Role**.
- No requiere abrir reglas de Security Group para el puerto de base de datos — se accede por HTTPS público.
- Free tier "para siempre" (25 GB + 25 WCU/RCU al mes), no solo los 12 meses del free tier de RDS.
- El modelo de datos (catálogo de productos, sin relaciones complejas) no necesita SQL.

## 0. Conceptos básicos de DynamoDB (si nunca la usaste)

Si vienes de PostgreSQL/MySQL con JPA, hay varias ideas nuevas que conviene tener claras antes de leer el código.

### Es NoSQL, no relacional

No hay tablas con columnas fijas, ni `JOIN`, ni SQL. Cada "fila" (en DynamoDB se llama **item**) es como un documento JSON: puede tener distintos atributos de un item a otro dentro de la misma tabla. Por eso se dice que es *schemaless* — al crear la tabla solo defines **cómo se identifica cada item**, no todos sus campos.

### La clave primaria: partition key (y opcionalmente sort key)

Todo item necesita una clave que lo identifique de forma única:

- **Partition key** (obligatoria): es el equivalente al `id`/`PRIMARY KEY` de SQL. DynamoDB la usa internamente para decidir *en qué servidor físico* guarda cada item — por eso se llama "de partición": reparte (particiona) los datos entre varios servidores para escalar. En este proyecto es `id` (String, un UUID).
- **Sort key** (opcional): una segunda parte de la clave, útil cuando varios items comparten la misma partition key y quieres ordenarlos/filtrarlos entre ellos (ej. `userId` + `fecha`). El catálogo de productos no la necesita — cada producto se identifica solo por su `id`.

### No hay autoincremento

En SQL, `@GeneratedValue(strategy = GenerationType.IDENTITY)` le pide a la base que genere el próximo número. DynamoDB no tiene ese concepto — **el id lo genera tu aplicación** antes de guardar el item. Por eso en `ProductController` se usa `UUID.randomUUID().toString()` al crear un producto, en vez de dejar que la "base" asigne un número.

### Las operaciones básicas (no hay SQL)

En vez de `SELECT`/`INSERT`/`UPDATE`/`DELETE`, DynamoDB tiene un puñado de operaciones fijas:

| Operación | Qué hace | Equivalente SQL aproximado |
|---|---|---|
| `PutItem` | Crea o **reemplaza por completo** un item | `INSERT` / `UPDATE` (sin `WHERE`, siempre por clave) |
| `GetItem` | Trae un item por su clave exacta | `SELECT ... WHERE id = ?` |
| `Scan` | Recorre **toda la tabla**, item por item | `SELECT * FROM tabla` (sin índice — caro si la tabla crece) |
| `Query` | Busca por partition key (y opcionalmente rango de sort key) | `SELECT ... WHERE partition_key = ?` (usa índice, es la forma "barata" de leer) |
| `DeleteItem` | Borra un item por su clave | `DELETE ... WHERE id = ?` |

En este proyecto, `getAll()` usa `Scan` (porque queremos *todos* los productos igual, no hay filtro), y el resto de las operaciones puntuales usan `GetItem`/`PutItem`/`DeleteItem` por `id`. Si el catálogo creciera a millones de productos, `Scan` dejaría de ser apropiado — se necesitaría un índice secundario (**GSI**, Global Secondary Index) y `Query`, algo fuera del alcance de este proyecto.

### Modo de capacidad: Bajo demanda (on-demand)

DynamoDB cobra por **capacidad de lectura/escritura**, no por "servidor prendido" como una VM. Hay dos modos:

- **Provisioned**: reservas de antemano cuántas lecturas/escrituras por segundo esperas y pagas por eso, se te acaba si te excedes (a menos que actives auto-scaling).
- **On-demand** (el que se usa aquí): pagas por request real, sin planificar capacidad. Ideal para un demo/proyecto con tráfico bajo e impredecible — es literalmente la opción que elegimos al crear la tabla.

### Enhanced Client vs Cliente de bajo nivel

El SDK de AWS para Java tiene dos formas de hablarle a DynamoDB:

- **Cliente de bajo nivel** (`DynamoDbClient`): mandas y recibís `Map<String, AttributeValue>` a mano — muy verboso (tienes que envolver cada valor con su tipo: `AttributeValue.builder().s("texto")`, `.n("123")`, etc.).
- **Enhanced Client** (`DynamoDbEnhancedClient` + `DynamoDbTable<T>`): mapea automáticamente tus objetos Java (POJOs anotados) a items de DynamoDB y viceversa — es el equivalente "ORM-like" de JPA, aunque mucho más simple porque no hay relaciones que resolver. **Este proyecto usa el Enhanced Client** para no tener que serializar/deserializar a mano.

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

DynamoDB es *schemaless*: no hace falta declarar el resto de los atributos (`name`, `price`, `stock`) al crear la tabla — cada item puede tener sus propios atributos (ver sección 0).

## 2. IAM Role para la instancia EC2

La EC2 necesita un IAM Role con permisos sobre la tabla, para que el SDK de AWS pueda leer/escribir sin credenciales estáticas.

### ¿Por qué un IAM Role y no un usuario con `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`?

Un usuario IAM con llaves estáticas es una credencial que **no expira** hasta que alguien la rota manualmente — si se filtra (queda en un log, en un repo, en una imagen de Docker), sigue siendo válida indefinidamente. Un **IAM Role asociado a una instancia EC2** en cambio genera credenciales **temporales** (rotan solas cada pocas horas) a través del *metadata service* de la instancia (`http://169.254.169.254`) — el SDK de AWS las pide y renueva automáticamente, sin que el código ni quien despliega la app tengan que verlas nunca. Es el mismo principio que ya se aplicó con `EC2-DynamoDB-Access-Role` en vez de guardar una contraseña de base de datos (como hubiera pasado si seguíamos con Aurora).

### Pasos

1. **IAM → Roles → Create role** (si no existe ya uno).
2. Trusted entity: **AWS service → EC2**.
3. Adjuntar la policy **`AmazonDynamoDBFullAccess`** (lectura y escritura completa). Opcionalmente también `AmazonDynamoDBReadOnlyAccess`, aunque es redundante si ya tienes FullAccess.
4. Nombre del role, por ejemplo: `EC2-DynamoDB-Access-Role`.
5. **EC2 → Instances → selecciona tu instancia → Actions → Security → Modify IAM role** → selecciona el role recién creado (o el existente) → **Update IAM role**.

No se generan ni se copian llaves de acceso en ningún momento — el SDK las obtiene automáticamente del metadata service de la instancia mientras el role esté asociado.

> Para producción real, `AmazonDynamoDBFullAccess` es más permiso del que hace falta (le da acceso a *todas* las tablas de la cuenta). Lo ideal sería una policy propia que solo permita `dynamodb:PutItem`, `GetItem`, `Scan`, `UpdateItem`, `DeleteItem` sobre el ARN exacto de la tabla `Products` — se usó la policy administrada por simplicidad, dado el contexto de aprendizaje del proyecto.

## 3. Dependencias (`build.gradle`)

Se usa el AWS SDK v2 (Enhanced Client para DynamoDB), gestionado por su propio BOM (*Bill of Materials* — un `pom`/catálogo de versiones que garantiza que todos los módulos del SDK que uses sean compatibles entre sí, sin tener que fijar la versión de cada uno a mano):

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
    implementation 'software.amazon.awssdk:dynamodb-enhanced' // el cliente "ORM-like" (ver sección 0)
    implementation 'software.amazon.awssdk:dynamodb'           // el cliente base que usa el Enhanced Client por debajo
}
```

Ya **no** se usan `spring-boot-starter-data-jpa` ni el driver de PostgreSQL — se quitaron al migrar desde Aurora, porque DynamoDB no se habla por JDBC.

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

Ninguna es secreta porque no hay contraseñas ni llaves involucradas — solo son nombres/config. Compará esto con la configuración que hubiera hecho falta para Aurora (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, las tres secretas) — es la diferencia práctica más grande entre los dos enfoques.

## 5. Código

### `Product.java` — modelo mapeado a DynamoDB

```java
@DynamoDbBean
public class Product {

    private String id;      // partition key, generado como UUID en el código (no autoincremental como en SQL)
    private String name;
    private Double price;
    private Integer stock;

    public Product() {
    } // constructor vacío OBLIGATORIO: el Enhanced Client lo usa por reflexión para reconstruir el objeto al leer un item

    public Product(String id, String name, Double price, Integer stock) {
        this.id = id;
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

    @DynamoDbPartitionKey // le dice al Enhanced Client cuál getter es la clave primaria de la tabla
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    // el resto de los getters/setters (name, price, stock) no llevan anotación:
    // se guardan tal cual como atributos normales del item, con el mismo nombre que el getter (sin el "get").
}
```

Puntos clave para quien nunca vio esto:

- `@DynamoDbBean` marca la clase como "mapeable" — es el equivalente a `@Entity` de JPA, pero mucho más simple porque no hay que declarar relaciones (`@OneToMany`, `@ManyToOne`, etc.) — DynamoDB no las tiene.
- `@DynamoDbPartitionKey` va **sobre el getter**, no sobre el campo (a diferencia de `@Id` en JPA, que suele ir sobre el campo).
- No existe `@GeneratedValue` — por eso el `id` se genera con `UUID.randomUUID().toString()` en `ProductController`, antes de construir el `ProductResponseDto`.

### `DynamoDbConfig.java` — beans de Spring

```java
@Configuration
public class DynamoDbConfig {

    @Value("${app.dynamo.table-name}")
    private String tableName;

    // Cliente base: habla el protocolo HTTP/JSON de DynamoDB.
    // Región y credenciales se resuelven automáticamente (variable AWS_REGION + IAM Role de la EC2).
    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder().build();
    }

    // Envuelve al cliente base para poder trabajar con objetos Java (Product) en vez de mapas de atributos.
    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient dynamoDbClient) {
        return DynamoDbEnhancedClient.builder()
                .dynamoDbClient(dynamoDbClient)
                .build();
    }

    // "Apunta" el Enhanced Client hacia la tabla Products, usando el esquema que describe @DynamoDbBean/@DynamoDbPartitionKey.
    // TableSchema.fromBean() lee las anotaciones de Product.java por reflexión, una sola vez al arrancar.
    @Bean
    public DynamoDbTable<Product> productTable(DynamoDbEnhancedClient enhancedClient) {
        return enhancedClient.table(tableName, TableSchema.fromBean(Product.class));
    }
}
```

Esta clase reemplaza lo que en JPA hacía Spring Boot automáticamente (el `DataSource`, el `EntityManager`, etc.) — con DynamoDB hay que armar manualmente esta cadena de tres beans porque no hay un "starter" que lo autoconfigure por ti a partir de una URL de conexión.

### `ProductController.java` — CRUD usando el `DynamoDbTable<Product>`

El controller recibe el bean `DynamoDbTable<Product>` por inyección de dependencias y opera directo sobre la tabla, sin capa de repositorio intermedia (no existe el equivalente a `JpaRepository` acá — el propio `DynamoDbTable` ya cumple ese rol):

| Método del controller | Operación DynamoDB | Método HTTP | Roles permitidos |
|---|---|---|---|
| `getAll()` | `Scan` (recorre toda la tabla) | `GET /api/products` | Cualquier usuario autenticado |
| `getById(id)` | `GetItem` | `GET /api/products/{id}` | Cualquier usuario autenticado |
| `create(request)` | `PutItem` (con un `id` nuevo, generado con `UUID`) | `POST /api/products` | `Admin`, `Colaborador` |
| `update(id, request)` | `GetItem` + `PutItem` (reemplaza el item completo) | `PUT /api/products/{id}` | `Admin` |
| `partialUpdate(id, request)` | `GetItem` + `PutItem` (solo pisa los campos no nulos del body) | `PATCH /api/products/{id}` | `Admin` |
| `delete(id)` | `DeleteItem` | `DELETE /api/products/{id}` | `Admin` |

Detalle importante sobre `update`/`partialUpdate`: DynamoDB no tiene un `UPDATE ... SET campo = valor` como SQL en su forma más simple con el Enhanced Client tal como se usa aquí — `PutItem` **reemplaza el item entero**. Por eso el código primero hace `GetItem` para traer el item actual, modifica el objeto Java en memoria, y recién ahí hace `PutItem` con el objeto completo. (DynamoDB sí soporta actualizaciones parciales atómicas vía `UpdateItem`/`UpdateExpression`, pero no se usó acá para mantener el código simple y legible en un proyecto educativo.)

### `seedIfEmpty()` — datos de ejemplo

```java
@PostConstruct
void seedIfEmpty() {
    boolean empty = !productTable.scan().items().iterator().hasNext();
    if (empty) {
        save(new Product(UUID.randomUUID().toString(), "Producto A", 10.0, 100));
        // ...
    }
}
```

Se ejecuta una vez al arrancar la app (`@PostConstruct`). Hace un `Scan` para ver si la tabla está vacía y, si lo está, siembra 3 productos de ejemplo — útil para que la demo tenga datos sin tener que crearlos a mano la primera vez.

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
6. También puedes ver los items directamente en la consola: **DynamoDB → Tables → `Products` → pestaña "Explore table items"** — útil para confirmar que el `Scan`/`PutItem` desde el código realmente está escribiendo.

## Troubleshooting

- **`software.amazon.awssdk:bom:X.Y.Z not found`**: la versión fijada en `build.gradle` puede estar desactualizada; revisa la última en [mvnrepository.com](https://mvnrepository.com/artifact/software.amazon.awssdk/bom).
- **`AccessDeniedException` al leer/escribir la tabla**: el IAM Role no está asociado a la instancia, o la policy no incluye permisos sobre esa tabla — revisa el paso 2.
- **La tabla no aparece / `ResourceNotFoundException`**: el nombre en `DYNAMO_TABLE_NAME` no coincide exactamente (mayúsculas/minúsculas) con el nombre real de la tabla, o la región (`AWS_REGION`) no es la misma donde se creó la tabla.
- **`software.amazon.awssdk.core.exception.SdkClientException: Unable to load region`**: falta la variable de entorno `AWS_REGION` en el contenedor — revisa `deploy.yml`.
- **Los cambios de `PUT`/`PATCH` no se reflejan**: recordar que `PutItem` reemplaza el item completo — si el objeto que se guarda no trae todos los campos, los que falten quedan `null` en el item guardado.

## Glosario rápido

| Término | Significado |
|---|---|
| **Item** | El equivalente a una "fila" en DynamoDB (documento tipo JSON) |
| **Atributo** | El equivalente a una "columna" — cada item puede tener atributos distintos |
| **Partition key** | Parte de la clave primaria que determina en qué partición física vive el item; en este proyecto, el `id` |
| **Sort key** | Segunda parte opcional de la clave primaria, para ordenar/agrupar items que comparten partition key (no se usa acá) |
| **GSI** (Global Secondary Index) | Índice adicional para poder consultar por otro atributo distinto de la clave primaria (no se usa acá, pero sería el siguiente paso si se necesitara buscar productos por nombre, por ejemplo) |
| **Enhanced Client** | Capa del SDK de AWS que mapea objetos Java anotados (`@DynamoDbBean`) a items, similar en espíritu a un ORM |
| **On-demand** | Modo de capacidad donde se paga por request real, sin reservar throughput de antemano |
