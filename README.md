# ms-barriodigital-requests

Microservicio de **trámites** del municipio (BarrioDigital).

Puerto: **8081**

Estados del caso: `INGRESADO → ADMITIDO → EN_GESTION → EN_TERRENO → RESUELTO | RECHAZADO`

## Cómo iniciar (Oracle — EP1-21 / EP1-14)

Requiere JDK 21, `JAVA_HOME` apuntando a ese JDK, acceso a Oracle Autonomous y su wallet. Maven Wrapper
descarga Maven y las dependencias en la primera ejecución; no hace falta instalar Maven por separado.

1. Copia las variables a un archivo local:

```powershell
copy .env.example .env
notepad .env
```

2. Completa el archivo `.env` (los valores reales solo van en tu PC):

| Variable | Qué va |
|----------|--------|
| `ORACLE_URL` | `jdbc:oracle:thin:@<alias>?TNS_ADMIN=<ruta-wallet>` |
| `ORACLE_USER` | Usuario de la aplicación (por ejemplo `barriodigital`); no hace falta usar ADMIN |
| `ORACLE_PASSWORD` | Contraseña de ese usuario |
| `ORACLE_WALLET_DIR` | Carpeta del wallet descomprimido **fuera** del repositorio |
| `RABBIT_ADDRESSES` | Broker AMQP. En local, `localhost:5672` |
| `RABBIT_USER` | Usuario de RabbitMQ |
| `RABBIT_PASSWORD` | Password de RabbitMQ |

Ejemplo de formato (cambia las rutas y el alias por los tuyos):

```env
ORACLE_URL=jdbc:oracle:thin:@barriodig_low?TNS_ADMIN=C:/Users/alm/OneDrive/6tosemestre/Wallet_BARRIODIG
ORACLE_USER=barriodigital
ORACLE_PASSWORD=********
ORACLE_WALLET_DIR=C:/Users/alm/OneDrive/6tosemestre/Wallet_BARRIODIG
```

El `<alias>` tiene que existir en el archivo `tnsnames.ora` del wallet (`*_low`, `*_medium` o `*_high`).

3. Inicia la aplicación desde la raíz del módulo (así se carga el `.env`):

```powershell
.\mvnw.cmd spring-boot:run
```

Si la conexión funciona, en el log aparece algo como: `Oracle OK: pool conectado (...)`.  
También puedes revisar: `http://localhost:8081/actuator/health` (el componente `db` debe estar en UP).

Al arrancar, Hibernate crea o actualiza la tabla `municipal_requests` (`ddl-auto: update`). El SQL de referencia está en `src/main/resources/db/schema.sql`.

### Dónde va el wallet

- Descárgalo desde Oracle Cloud → Autonomous Database → **Database connection** → **Download wallet**.
- Extrae el ZIP en una carpeta **fuera** de este repositorio.
- Ahí deben estar `tnsnames.ora`, `sqlnet.ora`, `cwallet.sso`, entre otros.
- `ORACLE_WALLET_DIR` debe apuntar a esa carpeta **real**.
- El alias de `ORACLE_URL` debe coincidir con un nombre de `tnsnames.ora`.
- No subas el ZIP ni la carpeta `wallet/` a Git.

### Qué no va a Git

- `.env` (contraseñas)
- Wallet (`*.p12`, `*.jks`, carpeta wallet)
- `application-local.yml`

## Endpoints (persistidos en Oracle — EP1-14)

Contrato alineado con el formulario del Vecino (tipo + descripción + dirección):

| Campo | Origen |
|-------|--------|
| `description`, `procedureType`, `address` | Body del POST |
| `title` | **Autogenerado** (`{etiqueta} — {dirección}`). No va en el body |
| `solicitanteId` | Header `X-User-Id` (oid/sub del JWT vía BFF). **Nunca** del body |
| `solicitanteNombre` | Header `X-User-Name` solo en el POST. Se guarda en la fila. Si el header viene vacío, queda el `solicitanteId`; si tampoco hay id, `Sin nombre`. GET y PUT de estado no lo recalculan |
| `solicitanteEmail` | Header `X-User-Email` solo en el POST. Se guarda tal cual, recortado. Si no viene o viene en blanco, queda `null` (también los trámites anteriores a esta columna). GET y PUT de estado no lo recalculan. EP2-20 del BFF es quien debe reenviar ese header |
| `procedureLabel` | Header `X-Procedure-Label` solo en el POST, codificado en URL (UTF-8). El BFF lo arma con el `label` de catalog. Se decodifica, se recorta y se trunca a 120. Si no viene, viene en blanco o no se puede decodificar, queda `null` sin error (también los trámites anteriores a esta columna). GET y PUT de estado no lo recalculan |
| Roles | Header `X-User-Roles` (ej. `Vecino` o `Funcionario`) |

`procedureType` es el **código** del tipo, no el label. Los tipos válidos los define `ms-barriodigital-catalog` y el BFF revisa que el código exista y esté activo antes de llamar a requests.  
Requests solo valida el formato: tras recortar espacios y pasar a minúsculas, tiene que cumplir `^[a-z0-9_-]{1,40}$`. Si no, responde **400**.  
El título usa `procedureLabel` si llegó en el header. Si no, usa la etiqueta conocida de `bache`, `alumbrado`, `basura`, `agua`, `ruido` y `otro`, y para un código nuevo el código tal cual.  
Catálogo (**deprecado**): `GET /api/requests/meta/procedure-types` sigue respondiendo la lista fija, pero la fuente de verdad es `GET /api/catalog/procedures`.  
Transiciones válidas: `GET /api/requests/meta/transitions`  
(mapa estado → siguientes; `RESUELTO` y `RECHAZADO` vienen con lista vacía)

Filtros de listado: `status`, `from`, `to` como **fecha** `yyyy-MM-dd` (sirve para mat-datepicker).  
Vecino: el servidor fuerza `solicitanteId = X-User-Id`. Funcionario/Admin/Auditor: ven todos.

```powershell
# Crear (como Vecino)
curl -X POST http://localhost:8081/api/requests `
  -H "Content-Type: application/json" `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino" `
  -H "X-User-Name: Alex Ampuero" `
  -H "X-User-Email: vecino@example.com" `
  -H "X-Procedure-Label: Bache%20en%20calle" `
  -d "{\"description\":\"Hueco frente al 123\",\"procedureType\":\"bache\",\"address\":\"Plaza Los Heroes\"}"

# Listar (fecha simple)
curl "http://localhost:8081/api/requests?status=INGRESADO&from=2026-09-01&to=2026-09-13" `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino"

# Por id
curl http://localhost:8081/api/requests/<id> `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino"

# Cambiar estado (solo Funcionario; EP1.5-05)
curl -X PUT http://localhost:8081/api/requests/<id>/status `
  -H "Content-Type: application/json" `
  -H "X-User-Id: oid-funcionario" `
  -H "X-User-Roles: Funcionario" `
  -d "{\"status\":\"ADMITIDO\"}"
```

Si la tabla ya tenía filas viejas sin `address`/`solicitante_id`, vaciala una vez en SQL:  
`DELETE FROM municipal_requests;` (o drop + recrear). Hibernate agrega las columnas con `ddl-auto=update`.

Colección Postman: `postman/EP1-14-requests.postman_collection.json`.

Para evidenciar en Oracle:  
`SELECT id, title, address, solicitante_id, status, created_at FROM municipal_requests ORDER BY created_at DESC;`

### Imagen de respaldo opcional

El alta acepta una única imagen opcional mediante `multipart/form-data`: la parte `request` contiene el mismo JSON descrito arriba y la parte `attachment` contiene la imagen. Se permiten JPEG y PNG; la interfaz también acepta WebP y lo convierte a PNG antes de enviarlo. El servicio verifica y decodifica los bytes (no confía en el nombre ni en el MIME declarado), limita el archivo a 5 MB y a 6000 px por lado/12 MP, y vuelve a codificarlo para quitar metadatos. SVG y otros formatos no se aceptan.

Los bytes normalizados se guardan como BLOB en `municipal_request_attachments`, separados de los listados; `hasAttachment` solo indica si hay una imagen. `GET /api/requests/{id}/attachment` entrega la imagen al titular o a Funcionario/Admin/Auditor, y oculta con 404 los trámites ajenos. El BFF aplica autenticación JWT y es el único acceso público; el servicio requests no debe exponerse directamente.

## Docker (EP1-24)

La imagen **no** lleva wallet ni `.env` adentro: se montan por volumen y variables.

```powershell
# Build
docker build -t bd-requests .

```

Health: `http://localhost:8081/actuator/health`  
El Compose de infraestructura suministra las variables Rabbit y Oracle y monta el wallet; su plantilla está versionada.

## Concurrencia de estado (EP1.5-18)

EP1.5-18 protege `changeStatus()` con `findByIdForUpdate()` y bloqueo pesimista de escritura por trámite. La misma transacción lee, valida la transición, modifica y persiste, para todas las transiciones. Dos admisiones del mismo trámite no validan simultáneamente INGRESADO: una completa y la otra recibe el 409 existente al observar ADMITIDO. También se serializan admisión y rechazo; trámites distintos no usan un bloqueo global. Los errores de bloqueo/persistencia no se convierten en conflictos de negocio.

La prueba `RequestStatusConcurrencyTest` usa dos transacciones independientes y H2, siguiendo Catalog. H2 se agrega solo con alcance test y versión del BOM; no valida Oracle desplegado. Ejecutar `.\mvnw.cmd verify`. Esta protección no convierte la coordinación con Catalog en una transacción distribuida ni garantiza recuperación ante respuestas perdidas.

## Eventos del trámite (EP2-22 / EP2-23 / EP2-24)

El alta confirmada en `INGRESADO` publica `certificate.receipt`. `INGRESADO → ADMITIDO` publica
`email.admitted`; `EN_TERRENO → RESUELTO` publica `email.resolved` y `certificate.resolved`.
Estos mensajes usan el exchange topic de `RabbitNamesProperties` (`cmd.topic`) y routing key igual al `type`.
`EN_GESTION → EN_TERRENO` publica `crew.ticket` en el exchange direct configurado (`cmd.direct`), con
routing key `crew.ticket`, y `email.visit` en el topic configurado, con routing key `email.visit`.
Las demás transiciones y las lecturas no publican eventos. Un estado repetido conserva el 409.

La publicación es asíncrona y posterior al commit, también cuando existe una transacción exterior. Rollback,
fallo de commit o transición rechazada no producen eventos. El payload incluye `requestId`, `procedureType`,
`procedureLabel`, `address` y `solicitanteEmail`, usando los snapshots persistidos. Un correo null se publica
igualmente y Notify lo rechaza hacia la DLQ de email, sin impedir el procesamiento del ticket de cuadrilla
o del certificado. Los certificados no requieren correo.
El productor conserva los null; Notify usa el código como respaldo de presentación de una etiqueta null.

Cada alta o cambio captura un único snapshot inmutable y registra un solo callback posterior al commit.
Cada par comparte ese snapshot, el `correlationId` del trámite y un `traceId` de la operación; cada mensaje tiene su propio
`eventId` y su instante de publicación. Los workers no reciben entidades JPA ni consultan repositorios.

Un fallo del broker o rechazo del ejecutor conserva HTTP 201 del alta o HTTP 200 del cambio ya persistido.
Un fallo de persistencia no se oculta ni se convierte en un alta exitosa.
Mensajes persistentes, mandatory y confirms aportan diagnóstico, no acreditan procesamiento ni recuperación.
Sin outbox ni reintentos del productor, una caída del broker, saturación o caída del proceso puede perder el evento.
Cada publicación se encola por separado y el fallo de una no suprime el intento de la otra. Se admite entrega
parcial; no se garantiza atomicidad del par ni orden de llegada o procesamiento.

Para el arranque y la operación, ver el repo `barriodigital-infra` → `apps/README.md`.

### Pruebas y validación

Desde la raíz de este repositorio, con JDK 21 y `JAVA_HOME` configurado, ejecutar Maven Wrapper:

```powershell
java -version
.\mvnw.cmd -B verify
```

En Linux/macOS, usar `sh ./mvnw -B verify`. Las pruebas utilizan la configuración versionada de
`src/test/resources`, H2 de test y transporte Rabbit simulado; no requieren Oracle, wallet, broker, Notify
ni archivos de una ejecución anterior. Cubren transacciones reales, rollback, fallo de commit, snapshots,
concurrencia, identidad y destinos de ambos pares, fallos parciales, HTTP 201/200 y rechazo del ejecutor.
`RequestStatusEventsHttpTest` utiliza HTTP y ejecutor reales con transporte simulado. Las llamadas verificadas
al publicador no acreditan recepción en un broker; la prueba con envío bloqueado no equivale a una prueba de red.

### Integración local comprobada

El **18/10/2026**, EP2-24 se comprobó con la aplicación H2 de test existente, RabbitMQ y Notify reales,
con `EMAIL_MODE=logging`. Se identificaron los PDF de ingreso y resolución por sus `eventId`, se leyeron
sus datos y se revisaron páginas renderizadas. Con correo null se generó el certificado y `email.resolved`
terminó en DLQ; la resolución repetida devolvió 409 sin nuevos efectos. Con la aplicación RabbitMQ detenida,
alta y resolución conservaron HTTP 201/200 y su estado persistido en menos de dos segundos, con fallos
registrados desde los workers. Esta comprobación no valida Oracle ni el despliegue EC2.

## Qué sigue

- Recuperación durable de publicaciones, fuera del alcance actual sin outbox.
