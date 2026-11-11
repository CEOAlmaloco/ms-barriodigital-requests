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
| `folio` | **Autogenerado** en el alta: `BD-AAAA-NNNNNN`, único y que no cambia. Ver [Folio](#folio-ep3-28) |
| `solicitanteId` | Header `X-User-Id` (oid/sub del JWT vía BFF). **Nunca** del body |
| `solicitanteNombre` | Header `X-User-Name` solo en el POST, codificado en URL (UTF-8) por el BFF. Se decodifica y se trunca a 200, igual que el `actor_name` del historial. Si el header viene vacío, mal codificado o con caracteres de control, queda el `solicitanteId`; si tampoco hay id, `Sin nombre`. GET y PUT de estado no lo recalculan |
| `solicitanteEmail` | Header `X-User-Email` solo en el POST. Se guarda tal cual, recortado. Si no viene o viene en blanco, queda `null` (también los trámites anteriores a esta columna). GET y PUT de estado no lo recalculan. EP2-20 del BFF es quien debe reenviar ese header |
| `procedureLabel` | Header `X-Procedure-Label` solo en el POST, codificado en URL (UTF-8). El BFF lo arma con el `label` de catalog. Se decodifica, se recorta y se trunca a 120. Si no viene, viene en blanco o no se puede decodificar, queda `null` sin error (también los trámites anteriores a esta columna). GET y PUT de estado no lo recalculan |
| Roles | Header `X-User-Roles` con **exactamente uno** de `Vecino`, `Funcionario`, `Admin` o `Auditor` (con o sin `ROLE_`). Sin rol, 403 `No tienes un rol asignado en BarrioDigital`; con más de uno, 403 `El usuario debe tener un solo rol`. `/api/requests/meta/**` queda fuera porque el BFF la llama sin identidad |
| `hasAssignee`, `assigneeId`, `assigneeName` | El responsable del trámite. El Vecino recibe solo `hasAssignee`; `assigneeId` y `assigneeName` le llegan `null`. Ver [Asignación](#asignación-y-operación-por-rol-ep3-29) |

`procedureType` es el **código** del tipo, no el label. Los tipos válidos los define `ms-barriodigital-catalog` y el BFF revisa que el código exista y esté activo antes de llamar a requests.  
Requests solo valida el formato: tras recortar espacios y pasar a minúsculas, tiene que cumplir `^[a-z0-9_-]{1,40}$`. Si no, responde **400**.  
El título usa `procedureLabel` si llegó en el header. Si no, usa la etiqueta conocida de `bache`, `alumbrado`, `basura`, `agua`, `ruido` y `otro`, y para un código nuevo el código tal cual.  
Catálogo (**deprecado**): `GET /api/requests/meta/procedure-types` sigue respondiendo la lista fija, pero la fuente de verdad es `GET /api/catalog/procedures`.  
Transiciones válidas: `GET /api/requests/meta/transitions`  
(mapa estado → siguientes; `RESUELTO` y `RECHAZADO` vienen con lista vacía)

Filtros de listado: `status`, `from`, `to` como **fecha** `yyyy-MM-dd` (sirve para mat-datepicker). Son días civiles en `America/Santiago` y los dos se incluyen completos: `to=2026-11-06` incluye un trámite creado ese día a las 22:00 en Chile, aunque en UTC ya sea el 7.  
Filtro `assignee=me|none`, solo para staff: `me` son los trámites de quien llama (vacío para el Admin y el Auditor) y
`none` los que no tienen responsable. Otro valor, 400; el Vecino que lo usa, 403.
Vecino: el servidor fuerza `solicitanteId = X-User-Id`. Funcionario/Admin/Auditor: ven todos.
Las respuestas salen como `RequestView`, armado según el rol: la entidad nunca se serializa. El Auditor recibe
`solicitanteEmail` en `null`.

```powershell
# Crear (como Vecino)
curl -X POST http://localhost:8081/api/requests `
  -H "Content-Type: application/json" `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino" `
  -H "X-User-Name: Alex%20Ampuero" `
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

# Tomar el trámite (Funcionario; EP3-29). Solo el responsable lo opera
curl -X POST http://localhost:8081/api/requests/<id>/take `
  -H "X-User-Id: oid-funcionario" `
  -H "X-User-Roles: Funcionario" `
  -H "X-User-Name: Ana%20Funcionaria"

# Cambiar estado (el Funcionario responsable, o el Admin con interventionReason; EP3-29)
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
`SELECT id, folio, title, address, solicitante_id, status, created_at FROM municipal_requests ORDER BY created_at DESC;`

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

## Folio (EP3-28)

Cada trámite recibe en el alta un número de seguimiento `BD-AAAA-NNNNNN`, por ejemplo `BD-2026-000123`
(contrato EP3-05 en `barriodigital-infra/docs/decisiones.md`). Va en las respuestas de la API y en el payload
de RabbitMQ.

- `AAAA` es el año de `created_at` en `America/Santiago`: un alta del 31-dic a las 23:30 en Chile es de ese año.
- `NNNNNN` es un correlativo sin huecos que reinicia cada año. Sale de la tabla `folio_counter` (una fila por año
  con el último número entregado), no de una secuencia de Oracle. Un alta revertida devuelve su número.
- El instante del alta se lee una sola vez del `Clock`: de él salen `created_at`, el año del folio y la marca
  `CREADO` del historial.
- `RequestCreationOrchestrationService` es el único punto de entrada de las dos altas. Crea la fila del año en
  una transacción corta, antes del alta, y el alta toma el número con la fila bloqueada, justo antes de guardar.
  Las altas simultáneas se procesan en fila.
- La columna es `NOT NULL`, única (`uk_municipal_requests_folio`) y no se actualiza. Más de 999.999 trámites en un
  año responde 500: es un límite por diseño.

### Trámites existentes

Una base que ya tiene trámites necesita, **una sola vez**, `src/main/resources/db/folio-existing-requests.sql`.
`ddl-auto: update` no puede agregar el `NOT NULL` sobre filas existentes, por eso no basta con arrancar la versión
nueva. Orden de la ventana de mantenimiento:

1. **Detener requests.** La versión anterior no conoce `folio`: si sigue creando trámites, el alta falla o deja
   filas sin folio.
2. **Correr el script** en Oracle, con SQLcl, SQL*Plus o el worksheet de SQL Developer. Se detiene ante el primer
   error. Empieza con una vista previa de solo lectura, que muestra el folio que recibirá cada trámite y se puede
   correr sola antes. Después agrega la columna, numera por año en orden de `created_at` (con `id` como desempate), aplica
   `NOT NULL` y `UNIQUE` e inicializa `folio_counter` con el máximo de cada año, el año en curso y el siguiente.
3. **Revisar la comprobación final** del script: `sin_folio` debe ser 0.
4. **Desplegar EP3-28 y levantar requests.**

Solo una base nueva, donde `municipal_requests` todavía no existe, se salta el script: Hibernate crea la tabla con
`folio` `NOT NULL`, el constraint y `folio_counter`. Si la tabla ya existe, aunque esté vacía, se corre el script.

## Asignación y operación por rol (EP3-29)

Implementa los contratos EP3-04 (capacidades por rol) y EP3-05 (asignación) de `barriodigital-infra/docs/decisiones.md`.
Las reglas viven en requests aunque el BFF también las aplique: requests no confía solo en el filtro del BFF.

**Quién hace qué**

| Acción | Vecino | Funcionario | Admin | Auditor |
|---|---|---|---|---|
| Crear trámite | Sí | No | No | No |
| Tomar un trámite sin responsable | No | Sí | No (asigna) | No |
| Asignar por primera vez | No | No (toma) | Sí | No |
| Reasignar, con motivo | No | Solo los suyos | Cualquiera | No |
| Cambiar estado y registrar gestiones | No | Solo como responsable | Sí, con `interventionReason` | No |
| Ver la lista de funcionarios | No | Sí | Sí | No |

- Crear siendo staff responde 403 `Solo un Vecino puede crear un tramite`.
- El Funcionario que no es el responsable, o un trámite sin responsable, responde 403
  `Solo el responsable del tramite puede operarlo`. La regla se revisa con la fila bloqueada (`findByIdForUpdate`),
  así que una reasignación simultánea no deja pasar al responsable anterior.
- El Admin opera cualquier trámite como **intervención**: `interventionReason` (hasta 500 caracteres) es obligatorio
  en `PUT /status` y `POST /gestiones`. Se guarda en `request_activity.intervention_reason` y es interno: el Vecino
  nunca lo recibe y no entra en el `summary`. Para el Funcionario se ignora.
- El Vecino y el Auditor que intentan operar conservan los textos de siempre
  (`Solo un Funcionario puede cambiar el estado` y `Solo un Funcionario puede registrar una gestión`), porque el
  BFF y el frontend los comparan hasta que EP3-67 traiga códigos estables.

**Endpoints**

| Endpoint | Roles | Body | Respuesta |
|---|---|---|---|
| `POST /api/requests/{id}/take` | Funcionario | Sin body | 200 con `RequestView` |
| `PUT /api/requests/{id}/assignee` | Admin y el Funcionario responsable | `{ "assigneeId": "...", "reason": "..." }` | 200 con `RequestView` |
| `GET /api/requests/assignees` | Funcionario, Admin | | 200 con `[{ "id": "...", "name": "..." }]` |

- `reason` es obligatorio al reasignar (400 `reason es obligatorio al reasignar`) y se ignora en la primera
  asignación. Su tope de 500 se valida siempre.
- Tomar o asignar un trámite cerrado, 409 `No se puede asignar un tramite cerrado`. Tomar uno con responsable,
  409 `El tramite ya tiene responsable`. Reasignar al mismo responsable, 409
  `El tramite ya esta asignado a ese funcionario`.
- El Vecino o el Auditor en estos endpoints, 403 `No tienes el rol necesario para este recurso`, el mismo mensaje del
  BFF. El Admin en `take`, 403 `Solo un Funcionario puede tomar un tramite`.
- La tabla completa de errores, con su orden, está en EP3-05, sección 4.

**Lista de funcionarios (`staff_member`)**

La plataforma no consulta el directorio de Entra ID. Un `HandlerInterceptor` registra al personal en cada llamada con
`X-User-Id` y exactamente un rol de staff, con el nombre decodificado y en una transacción propia. Escribe a lo más
una vez cada 15 minutos por `oid`, salvo que cambie el nombre o el rol. Un fallo del registro queda en el log y no
bloquea la llamada. Los vecinos no se registran. Un Funcionario aparece en la lista desde su primera llamada.

**Historial**

Cada toma, asignación o reasignación agrega una fila `ASIGNACION` `INTERNO` ("Tomó el trámite", "Asignó a…",
"Reasignó de… a…. Motivo: …"). Solo la primera asignación agrega además un hito `PUBLICO` sin actor:
"Tu solicitud fue asignada para su atención". El Vecino ve solo el hito; el staff, solo la fila interna. Para el
Vecino, los eventos hechos por staff llevan `actorId: null` y `actorName: "Municipalidad"`. `tiempos` calcula el
tiempo de asignación (`primeraAsignacionSegundos`) solo para staff.

### Bases existentes: `assignment-request-activity.sql`

`ddl-auto: update` agrega las columnas nuevas, pero no relaja el `NOT NULL` de `request_activity.actor_id` ni cambia
el `CHECK` que Hibernate creó para `kind` con `CREADO`, `ESTADO` y `GESTION`. Sin el script, la primera toma falla en
Oracle con ORA-02290. Corre **una vez**, antes de que alguien tome o asigne un trámite:

1. **Revisar la vista previa** del script: muestra el `CHECK` de `kind` que se va a reemplazar (su nombre `SYS_C…`
   cambia en cada base).
2. **Correr el script** con SQLcl, SQL*Plus o el worksheet de SQL Developer ("Ejecutar script"). Deja `actor_id`
   nullable, reemplaza el `CHECK` de `kind` por `ck_request_activity_kind` con `ASIGNACION`, y agrega
   `ck_request_activity_actor`: solo el hito público va sin actor.
3. **Revisar la comprobación final**: `actor_id` nullable y los dos `CHECK` nuevos.

Una base nueva no lo necesita: Hibernate crea la tabla con las cuatro marcas y con el `CHECK` del actor.

**Transición hasta EP3-45 y EP3-47:** el BFF todavía no expone `take`, `assignee` ni `assignees`, y sigue aceptando
solo al Funcionario en el cambio de estado. Hasta esas issues, nadie puede tomar ni operar un trámite desde la
pantalla, y un 403 del no responsable en la admisión no libera el cupo reservado. No se despliega antes de EP3-45.

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
fallo de commit o transición rechazada no producen eventos. El payload incluye `requestId`, `folio`, `procedureType`,
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
