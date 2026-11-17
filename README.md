# ms-barriodigital-requests

Microservicio de **trámites** del municipio (BarrioDigital).

Puerto: **8081**

Estados del caso: `INGRESADO → ADMITIDO → EN_GESTION → EN_TERRENO → RESUELTO | RECHAZADO`, y `INGRESADO → CANCELADO` solo por el titular (`POST /api/requests/{id}/cancel`, EP3-76).

Encuesta (EP3-74): el titular califica un trámite `RESUELTO` una sola vez con `POST /api/requests/{id}/rating` (`score` 1..5, `comment` opcional) y queda `request.rated` en el outbox.
Preferencias de aviso (EP3-65): `GET|PUT /api/requests/me/notification-preferences` con `{ "emailEnabled": true|false }`.
Si el titular desactiva el correo, requests no publica `email.*` a RabbitMQ; `certificate.*` y `crew.ticket` salen igual.

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
| `KAFKA_BOOTSTRAP_SERVERS` | Brokers de Kafka. En local, `localhost:9092,localhost:9093,localhost:9094` |

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
| `slaBusinessDays` | Header `X-Procedure-Sla-Days` solo en el POST: el plazo del tipo en días hábiles, que el BFF toma de catalog (EP3-06). Es un snapshot: editar el tipo después no lo cambia. Sin header, `null` (tipo sin plazo, y trámites anteriores a EP3-31). Un valor que no es un entero de 1 a 120 queda `null` con un `WARN` en el log, sin bloquear el alta. Lo ven los cuatro roles |
| Roles | Header `X-User-Roles` con **exactamente uno** de `Vecino`, `Funcionario`, `Admin` o `Auditor` (con o sin `ROLE_`). Sin rol, 403 `No tienes un rol asignado en BarrioDigital`; con más de uno, 403 `El usuario debe tener un solo rol`. `/api/requests/meta/**` queda fuera porque el BFF la llama sin identidad |
| `hasAssignee`, `assigneeId`, `assigneeName` | El responsable del trámite. El Vecino recibe solo `hasAssignee`; `assigneeId` y `assigneeName` le llegan `null`. Ver [Asignación](#asignación-y-operación-por-rol-ep3-29) |

`procedureType` es el **código** del tipo, no el label. Los tipos válidos los define `ms-barriodigital-catalog` y el BFF revisa que el código exista y esté activo antes de llamar a requests.  
Requests solo valida el formato: tras recortar espacios y pasar a minúsculas, tiene que cumplir `^[a-z0-9_-]{1,40}$`. Si no, responde **400**.  
El título usa `procedureLabel` si llegó en el header. Si no, usa la etiqueta conocida de `bache`, `alumbrado`, `basura`, `agua`, `ruido` y `otro`, y para un código nuevo el código tal cual.  
Catálogo (**deprecado**): `GET /api/requests/meta/procedure-types` sigue respondiendo la lista fija, pero la fuente de verdad es `GET /api/catalog/procedures`.  
Transiciones válidas: `GET /api/requests/meta/transitions`  
(mapa estado → siguientes del PUT `/status`; `RESUELTO`, `RECHAZADO` y `CANCELADO` vienen con lista vacía. La cancelación del titular es `POST /api/requests/{id}/cancel`, EP3-76)

El listado `GET /api/requests` responde **por página**, con filtros, orden y búsqueda. Ver
[Listado paginado](#listado-paginado-ep3-30). `GET /api/requests/export` descarga ese mismo listado en CSV, ver
[Exportación CSV](#exportación-csv-ep3-63). Los posibles duplicados de un alta o de un trámite ya ingresado están en
[Posibles duplicados](#posibles-duplicados-ep3-75).
Vecino: el servidor fuerza `solicitanteId = X-User-Id`. Funcionario/Admin/Auditor: ven todos.
Las respuestas salen como `RequestView`, armado según el rol: la entidad nunca se serializa. El Auditor recibe
`solicitanteEmail` en `null`.

### Historial, gestiones, ubicación e imagen

Contrato: `barriodigital-infra/docs/decisiones.md` → «historial, gestiones, ubicación e imágenes».

| Ruta | Quién | Notas |
|------|-------|-------|
| `GET /api/requests/{id}/actividad` | titular o personal | Historial por rol + `tiempos`; filas `INTERNO` solo staff |
| `POST /api/requests/{id}/gestiones` | Funcionario responsable / Admin | Registra `GESTION`; nota e `interventionReason` según contrato |
| `GET /api/requests/{id}/attachment` | titular o personal | Imagen del alta; otro Vecino → 404 |
| Alta con `attachment` | Vecino | JPEG/PNG, ≤5 MB, ≤6000 px / 12 MP; WebP lo pasa el FE a PNG |
| Ubicación en el alta | Vecino | `sector` de Alto Biobío; lat/lon juntas dentro de `LocationRules` |

Por el API Gateway estas rutas entran por `/{proxy+}` con JWT (EP3-13).

```powershell
# Crear (como Vecino)
curl -X POST http://localhost:8081/api/requests `
  -H "Content-Type: application/json" `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino" `
  -H "X-User-Name: Alex%20Ampuero" `
  -H "X-User-Email: vecino@example.com" `
  -H "X-Procedure-Label: Bache%20en%20calle" `
  -H "X-Procedure-Sla-Days: 5" `
  -d "{\"description\":\"Hueco frente al 123\",\"procedureType\":\"bache\",\"address\":\"Plaza Los Heroes\"}"

# Listar (abiertos de septiembre que mencionan "bache", segunda página de 10)
curl "http://localhost:8081/api/requests?open=true&from=2026-09-01&to=2026-09-30&q=bache&page=1&size=10" `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino"

# Exportar a CSV los abiertos sin responsable (staff; el Vecino exporta solo los suyos)
curl "http://localhost:8081/api/requests/export?open=true&assignee=none" -o tramites.csv `
  -H "X-User-Id: oid-func-prueba" `
  -H "X-User-Roles: Funcionario"

# Posibles duplicados antes de crear (solo Vecino; EP3-75)
curl "http://localhost:8081/api/requests/duplicates?procedureType=bache&latitude=-37.8742&longitude=-71.5915&sector=ralco" `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino"

# Posibles duplicados de un trámite ingresado (Funcionario o Admin; EP3-75)
curl http://localhost:8081/api/requests/<id>/duplicates `
  -H "X-User-Id: oid-funcionario" `
  -H "X-User-Roles: Funcionario"

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

# Cancelar (solo el Vecino titular, solo en INGRESADO; EP3-76)
curl -X POST http://localhost:8081/api/requests/<id>/cancel `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino" `
  -H "X-User-Name: Alex%20Ampuero"

# Calificar (solo el Vecino titular, solo en RESUELTO, una vez; EP3-74)
curl -X POST http://localhost:8081/api/requests/<id>/rating `
  -H "Content-Type: application/json" `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino" `
  -H "X-User-Name: Alex%20Ampuero" `
  -d "{\"score\":5,\"comment\":\"Rapido\"}"

# Comentar con foto (solo el Vecino titular, en un trámite abierto; EP3-64)
curl -X POST http://localhost:8081/api/requests/<id>/comments `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino" `
  -H "X-User-Name: Alex%20Ampuero" `
  -F "comment={\"text\":\"Ahora se acumula agua\"};type=application/json" `
  -F "attachment=@foto.jpg;type=image/jpeg"
```

Si la tabla ya tenía filas viejas sin `address`/`solicitante_id`, vaciala una vez en SQL:  
`DELETE FROM municipal_requests;` (o drop + recrear). Hibernate agrega las columnas con `ddl-auto=update`.

Colección Postman: `postman/EP1-14-requests.postman_collection.json`.

Para evidenciar en Oracle:  
`SELECT id, folio, title, address, solicitante_id, status, created_at FROM municipal_requests ORDER BY created_at DESC;`

### Imagen de respaldo opcional

El alta acepta una única imagen opcional mediante `multipart/form-data`: la parte `request` contiene el mismo JSON descrito arriba y la parte `attachment` contiene la imagen. Se permiten JPEG y PNG; la interfaz también acepta WebP y lo convierte a PNG antes de enviarlo. El servicio verifica y decodifica los bytes (no confía en el nombre ni en el MIME declarado), limita el archivo a 5 MB y a 6000 px por lado/12 MP, y vuelve a codificarlo para quitar metadatos. SVG y otros formatos no se aceptan.

Los bytes normalizados se guardan como BLOB en `municipal_request_attachments`, separados de los listados; `hasAttachment` solo indica si hay una imagen. `GET /api/requests/{id}/attachment` entrega la imagen al titular o a Funcionario/Admin/Auditor, y oculta con 404 los trámites ajenos. El BFF aplica autenticación JWT y es el único acceso público; el servicio requests no debe exponerse directamente.

## Comentario y fotos del vecino (EP3-64)

Reglas, errores y evento en el contrato EP3-64 de `barriodigital-infra/docs/decisiones.md`.

- `POST /api/requests/{id}/comments`: JSON `{ "text": "..." }`, o `multipart/form-data` con la parte `comment` y la
  parte opcional `attachment`, que se valida como la imagen del alta. Responde 201 con el `ActividadView`.
- `GET /api/requests/{id}/comments/{commentId}/attachment`: la foto, con las reglas de acceso de `/attachment`.

### Antes de desplegar: `citizen-comment.sql`

Sin el script, el primer comentario falla con ORA-02290. Orden de despliegue:

1. **Correr** `src/main/resources/db/citizen-comment.sql` una vez, con SQLcl, SQL*Plus o el worksheet de SQL
   Developer ("Ejecutar script"). Se puede volver a correr, también tras una ejecución interrumpida: crea lo que
   falta y se detiene, sin tocarla, ante una restricción con el nombre esperado y otra definición.
2. **Revisar la comprobación final:** las seis restricciones `ENABLED` y `VALIDATED`, un solo `CHECK` de `kind` y
   `has_attachment` con default 0.
3. **Desplegar audit y report** con soporte para `request.citizen-comment`. Si requests se adelanta, los dos lo
   descartan con un WARN y ese evento no se puede reprocesar.
4. **Desplegar requests.**

En los tests, `src/test/resources/db/h2-citizen-comment.sql` crea la UNIQUE y la FK compuesta en H2.

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
  (`Solo un Funcionario puede cambiar el estado` y `Solo un Funcionario puede registrar una gestión`). El cambio de
  estado agrega además un `code` estable, ver [Errores y códigos](#errores-y-códigos-ep3-67).

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

El BFF reenvía `X-User-Name` en todas las llamadas. Si una llamada no lo trae, o viene vacío o ilegible, se conserva
el nombre guardado: una llamada sin nombre nunca pisa uno válido. Una fila nueva sin nombre usa el `oid` hasta la
primera llamada que lo traiga.

**Historial**

Cada toma, asignación o reasignación agrega una fila `ASIGNACION` `INTERNO` ("Tomó el trámite", "Asignó a…",
"Reasignó de… a…. Motivo: …"). Solo la primera asignación agrega además un hito `PUBLICO` sin actor:
"Tu solicitud fue asignada para su atención". El Vecino ve solo el hito; el staff, solo la fila interna. Para el
Vecino, los eventos hechos por staff llevan `actorId: null` y `actorName: "Municipalidad"`. `tiempos` calcula el
tiempo de asignación (`primeraAsignacionSegundos`) solo para staff.

### Bases existentes: `cancelado-status.sql`

`ddl-auto: update` no amplía los `CHECK` de `municipal_requests.status` ni de
`request_activity.from_status` / `to_status` que Hibernate creó sin `CANCELADO`. Sin el script, el primer
`POST /cancel` del titular falla en Oracle con ORA-02290. Corre **una vez**, antes de probar la cancelación:

1. **Revisar la vista previa** del script: muestra los `CHECK` a reemplazar (su nombre `SYS_C…` cambia en cada base).
2. **Correr** `src/main/resources/db/cancelado-status.sql` con SQLcl, SQL*Plus o el worksheet de SQL Developer.

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

### Reparación de nombres: `repair-assignee-names.sql`

Antes de que el BFF reenviara el nombre en las lecturas, `staff_member` podía quedar con el `oid` como nombre, y asignar
copiaba ese valor a `municipal_requests.assignee_name` y al historial. Los nombres son snapshots: el código corregido
no arregla los ya guardados. El script corrige solo los que son iguales a su id y cuya fila en `staff_member` ya
tiene un nombre válido (no vacío y distinto del `oid`). No toca `outbox_events`: los eventos emitidos son historia.

1. **Desplegar** este cambio y el del BFF que reenvía `X-User-Name`.
2. **Esperar** a que cada funcionario afectado vuelva a entrar: su primera llamada deja su nombre en `staff_member`.
3. **Revisar la vista previa** del script: las filas que se van a reparar, con el nombre nuevo, y los `oid` que
   todavía no tienen nombre (esos se saltan).
4. **Correr el script** con SQLcl, SQL*Plus o el worksheet de SQL Developer ("Ejecutar script"). Hace `COMMIT` al
   final y se puede repetir: lo reparado deja de cumplir la condición.
5. **Revisar la comprobación final**: los pendientes son solo los `oid` sin nombre todavía. Volver al paso 2 para ellos.
6. **Reparar report** con su propio `repair-assignee-names.sql`, que corrige la proyección de plazos.

## Listado paginado (EP3-30)

`GET /api/requests` devuelve una página. Report y audit usan la misma forma (EP3-07, sección 2.1), así el frontend
tiene un solo componente de tabla. Los filtros se combinan por un Y lógico, y un filtro vacío no se aplica.

| Parámetro | Regla | Si es inválido, 400 |
|---|---|---|
| `page` | Entero desde 0. Por defecto 0. Una página más allá del total viene vacía | `page debe ser 0 o mayor` |
| `size` | Entero de 1 a 50. Por defecto 20 | `size debe estar entre 1 y 50` |
| `sort` | `campo` o `campo,asc\|desc`, con `createdAt`, `status` o `folio`. Sin dirección, ascendente. Por defecto `createdAt,desc` | `sort no es valido` |
| `q` | Texto que se busca en el folio, la descripción y la dirección, sin distinguir mayúsculas. Hasta 100 caracteres | `q no puede superar 100 caracteres` |
| `status` | Un estado, por ejemplo `EN_GESTION` | Error de conversión de Spring |
| `open` | `true` son los abiertos y `false` los cerrados (`RESUELTO`, `RECHAZADO` o `CANCELADO`). Con `status`, Y lógico: `status=RESUELTO&open=true` da una página vacía | `open debe ser true o false` |
| `procedureType` | Código del tipo, sin distinguir mayúsculas. Un código que no existe da una página vacía, porque los tipos viven en catalog | |
| `sector` | Clave de sector del alta, por ejemplo `villa-ralco` | `sector no es valido` |
| `from`, `to` | Días `yyyy-MM-dd` en `America/Santiago`, los dos completos: `to=2026-11-06` incluye un trámite de ese día a las 22:00 en Chile, aunque en UTC ya sea el 7 | `from no puede ser posterior a to` |
| `assignee` | `me` o `none`, solo staff (EP3-05, sección 8). El Vecino que lo usa recibe 403 `El filtro assignee es solo para staff` | `assignee debe ser me o none` |
| `contactDue` | `true`, solo staff: trámites abiertos cuya última gestión agenda un reintento (`nextAttemptAt`) vencido o de hoy en `America/Santiago`. La última gestión es la última registrada, con el mismo orden del detalle (EP3-66). Para la bandeja: `assignee=me&contactDue=true`. El Vecino que lo usa recibe 403 `El filtro contactDue es solo para staff` | `contactDue debe ser true` |

```json
{
  "content": [ { "id": "…", "folio": "BD-2026-000123", "status": "EN_GESTION", "…": "…" } ],
  "page": 0,
  "size": 20,
  "totalElements": 57,
  "totalPages": 3
}
```

- `content` lleva `RequestView`, proyectado por rol como en el detalle. El Vecino solo ve sus trámites, también al
  buscar y al filtrar.
- El orden siempre desempata por `id`, así que un trámite no se repite ni se pierde entre páginas.
  `sort=status` sigue el ciclo de vida (`INGRESADO` → … → `RESUELTO` → `RECHAZADO` → `CANCELADO`), no el orden
  alfabético.
  Dentro de un mismo estado van primero los más recientes.
- `%`, `_` y `\` en `q` se buscan como texto, no como comodines.
- Para un conteo basta `size=1`: `totalElements` trae el total (bloques de `/inicio`, EP3-04, sección 7).
- Índices en `municipal_requests` para el Vecino, el orden por defecto y la bandeja:
  `(solicitante_id, created_at)`, `(created_at)` y `(assignee_id)`. Hibernate los crea por nombre al arrancar.

**Límites conocidos**

1. **`q` distingue tildes:** "Cauñicú" no encuentra "Caunicu". Ignorarlas igual en Oracle y en H2 requiere Oracle
   Text o una columna normalizada; queda como mejora futura.
2. **`q` no usa índices:** busca con `LIKE '%texto%'`, que recorre la tabla. A la escala de una comuna responde
   bien. Si crece, el paso es Oracle Text.
3. **`status` acepta un solo valor**, como antes de EP3-30. Aceptar varios separados por coma, como en report, es
   una mejora futura.

## Orden del expediente (EP3-66)

`request_activity.activity_sequence` define el orden del expediente: la línea de tiempo de `/actividad`, el resumen
de contacto y la última gestión del filtro `contactDue`. Lo asigna Oracle al insertar, con `request_activity_seq`
(`ORDER`), y como cada escritura toma antes el bloqueo de la fila del trámite, una operación posterior siempre
recibe un valor mayor. `occurredAt` queda como hora informativa: dos marcas pueden tener el mismo instante y el reloj
del servidor puede retroceder. La columna no sale en la API ni en los eventos de Kafka.

### Antes de desplegar: `activity-sequence.sql`

Toda base que ya tiene `request_activity` lo necesita una vez, **sin escrituras concurrentes**:

1. **Detener requests.** Ningún insert puede ocurrir mientras el script numera las filas y crea la secuencia.
2. **Correr** `src/main/resources/db/activity-sequence.sql` con SQLcl, SQL*Plus o el worksheet de SQL Developer
   ("Ejecutar script"). Se puede volver a correr, también tras una ejecución interrumpida: completa lo que falta.
3. **Revisar la comprobación final:** `activity_sequence` con `NULLABLE: N` y `DEFAULT_ON_NULL: YES`,
   `request_activity_seq` con `ORDER_FLAG: Y`, `uk_request_activity_sequence` `ENABLED` y `VALIDATED`, y
   `sin_secuencia` en 0.
4. **Desplegar y levantar requests.**

Una base nueva creada con `db/schema.sql` ya trae la secuencia y la columna. Si fue Hibernate quien creó la tabla, la
columna queda sin valor automático y el primer insert falla con ORA-01400: detener requests y correr el script.

En los tests, `src/test/resources/db/h2-activity-sequence.sql` agrega la secuencia y la UNIQUE en H2, sin `ORDER`.

**Límites conocidos**

1. **Historial anterior al script:** sus filas se numeran por `occurred_at` e `id`, los únicos datos disponibles. Dos
   marcas antiguas con el mismo instante quedan en un orden estable, no necesariamente en el real. La garantía del
   orden de las operaciones aplica a las actividades registradas después del script.

## Exportación CSV (EP3-63)

`GET /api/requests/export` entrega el listado completo en CSV, sin paginar. Acepta los mismos filtros y el mismo
`sort` que `GET /api/requests`, con las mismas reglas y los mismos 400 y 403. `page` y `size` se ignoran. El contrato
está en `barriodigital-infra/docs/decisiones.md`, "Contrato: exportación CSV del listado (EP3-63)".

- **Roles:** los cuatro. El Vecino exporta solo sus trámites, también al buscar o filtrar, y recibe 403 si usa
  `assignee`. Cada fila sale de `RequestView`, con la misma proyección por rol del listado: el Vecino no recibe al
  responsable y el Auditor no recibe el correo del solicitante.
- **Tope de 5000 trámites.** Si el resultado lo supera, responde 400
  `La exportacion supera 5000 tramites. Agrega filtros`, sin filas. Un CSV recortado se leería como completo.
- **Instantánea:** es un solo `SELECT` que se recorre por lotes de 500. El conteo para el tope y las filas salen de la
  misma lectura, así que una escritura concurrente no repite ni omite trámites. Paginar con varias consultas sí lo
  haría.
- **Formato:** `text/csv; charset=UTF-8` con BOM, separador `;` y fin de línea CRLF, como lo abre Excel en
  configuración chilena. Todas las celdas van entre comillas. Fechas `yyyy-MM-dd HH:mm` en `America/Santiago`.
  Descarga `tramites-AAAA-MM-DD.csv`, con el día de Chile, y `Cache-Control: private, no-store`.
- **Fórmulas:** una celda cuyo primer carácter visible es `=`, `+`, `-` o `@` (también de ancho completo), o que
  empieza con un carácter de control o invisible, lleva `'` adelante. Así Excel la muestra como texto y no la ejecuta
  (CSV injection).

| Columna | Origen |
|---|---|
| Folio, Estado, Tipo, Título, Dirección, Sector | `folio`, `status`, `procedureLabel` (o el código), `title`, `address` y el nombre del sector |
| Ingreso, Última actualización | `createdAt`, `updatedAt` |
| Plazo (días hábiles), En atención | `slaBusinessDays`, `hasAssignee` como `Sí` o `No` |
| Responsable | `assigneeName`. Vacío para el Vecino |
| Solicitante, Correo solicitante | `solicitanteNombre`, `solicitanteEmail`. El correo, vacío para el Auditor |
| Motivo de rechazo | `rejectionReason`, que también ve el Vecino |

No lleva la descripción, la ubicación, la encuesta ni ids internos: el CSV es para gestión y no copia más datos
personales que los necesarios.

## Posibles duplicados (EP3-75)

Trámites **abiertos** del **mismo tipo** dentro del radio de un punto o, en su defecto, del mismo sector. Es solo
lectura: no escribe, no genera eventos y no bloquea el alta. El contrato está en
`barriodigital-infra/docs/decisiones.md`, "Contrato: aviso de posible duplicado (EP3-75)".

| Ruta | Roles | Base de la búsqueda |
|---|---|---|
| `GET /api/requests/duplicates` | Vecino | `procedureType` y un punto (`latitude` + `longitude`), un `sector` o ambos |
| `GET /api/requests/{id}/duplicates` | Funcionario, Admin | Tipo, punto y sector del propio trámite, que no aparece en el resultado |

- **Radio:** `barriodigital.duplicates.radius-meters` (150 m; de 10 a 2000). El cliente no lo elige. Tope:
  `max-results` (5; de 1 a 20). Fuera de rango, el servicio no arranca. Se pueden cambiar por entorno, por ejemplo
  `BARRIODIGITAL_DUPLICATES_RADIUSMETERS=200`.
- **Con punto:** entran los que tienen punto dentro del radio, y además, si viene `sector`, los de ese sector sin
  punto. **Sin punto:** todos los del sector. El sector `otro` no cuenta como coincidencia, pero no impide buscar
  por cercanía; solo con `otro` y sin punto, la lista viene vacía.
- **Orden:** primero los del radio, por distancia exacta; después los del sector, del más reciente al más antiguo.
  Los empates van por fecha descendente y después por id. El tope se aplica al final.
- **Respuesta:** lista de `folio`, `procedureType`, `procedureLabel`, `status`, `sector`, `distanceMeters`
  (redondeada a 10 m; `null` si coincidió por sector) y `createdDate` (día en `America/Santiago`). El Vecino recibe
  además `mine`; el personal, `id` y `hasAssignee`. Nunca lleva datos del solicitante, descripción, dirección,
  referencia, coordenadas, imagen ni responsable.

| Si | Responde |
|---|---|
| Falta `X-User-Id` | 400 `USER_ID_REQUIRED` |
| El rol no corresponde a la ruta | 403 |
| El trámite no existe (`/{id}/duplicates`) | 404 `REQUEST_NOT_FOUND` |
| Falta `procedureType` o tiene mal formato | 400 `procedureType es obligatorio` o el mismo mensaje del alta |
| Llega solo una coordenada, no es un número finito o cae fuera de la comuna | 400 `latitude y longitude van juntas`, `latitude y longitude deben ser numeros` o `El punto esta fuera de la comuna` |
| `sector` no es una clave del alta | 400 `sector no es valido` |
| No llega ni punto ni sector | 400 `Indica un punto o un sector` |

Índice `idx_municipal_requests_type_status (procedure_type, status)`, que Hibernate crea por nombre al arrancar.

**Límites conocidos**

1. **Sobre 5000 trámites** hay que exportar por partes, con filtros de fecha o estado.
2. **Excel con configuración en inglés** espera coma como separador: hay que importar el archivo indicando `;`.
3. **Sin registro de auditoría:** la exportación no deja evento en `audit.timeline`.

## Eventos de Kafka: outbox (EP3-31)

Implementa la mitad de requests de los contratos EP3-01 (eventos) y EP3-02 (publicación) de
`barriodigital-infra/docs/decisiones.md`. Cada hecho del trámite se guarda como una fila de `outbox_events` **en la
misma transacción** que el cambio y su marca del expediente: o quedan los tres, o ninguno. Un rollback, una falla al
escribir el outbox o una transición rechazada (409) no dejan evento. El relay que publica en `requests.events` es
EP3-32 (sección siguiente).

| Operación | Evento | Comandos de RabbitMQ |
|---|---|---|
| Alta | `request.created` | `certificate.receipt` |
| Cambio de estado | `request.status-changed` | Los de la sección de eventos, según la transición |
| Gestión de contacto | `request.contact-logged` | Ninguno |
| Comentario del titular | `request.citizen-comment` | Ninguno |
| Toma, asignación o reasignación | `request.assigned` | Ninguno |

- `DomainEventRecorder` (paquete `messaging`) arma el sobre de EP3-01 desde la fila del expediente: mismo instante,
  mismo actor y mismo rol. Exige la transacción del cambio (`Propagation.MANDATORY`) y no importa clases de Kafka.
- **Un evento por operación.** La primera asignación guarda dos marcas (la interna y el hito público), pero un solo
  `request.assigned`: el hito no genera evento (EP3-05, sección 7).
- **El JSON** se guarda sin `sequence`, que agrega el relay desde `event_sequence`. Los campos opcionales van siempre,
  con `null` si no aplican. No viajan el correo, la dirección textual ni la nota de la gestión (EP3-01, sección 8).
  `interventionReason` va en `status-changed` y `contact-logged`, `null` si el actor no es Admin.
- **`traceId`:** el evento y los comandos de RabbitMQ del mismo cambio comparten el mismo. RabbitMQ sigue publicando
  después del commit, igual que antes.
- **`actor_role`:** columna nueva de `request_activity` con el único rol de quien actuó (EP3-04, sección 4). El hito
  sin actor y las filas anteriores a EP3-31 quedan en `null`. `/actividad` lo entrega como `actorRole` solo al staff;
  el Vecino lo recibe `null`, también en sus propias marcas (EP3-04, sección 6.2).
- **Instantes:** Oracle y H2 guardan microsegundos y redondean el resto. Los instantes del alta, el cambio de estado,
  la gestión, la asignación y el comentario se truncan a microsegundos al nacer (`StoredInstant`), así el `occurredAt` del evento
  es exactamente el `occurred_at` de la marca.

### Antes de desplegar: `outbox-events.sql`

`ddl-auto: update` no sabe crear `event_sequence` como identity con `ORDER`. Si Hibernate crea la tabla primero, la
crea sin identity y la primera alta falla con ORA-01400. Por eso, **una vez y antes del primer arranque con
EP3-31**, también en una base nueva:

1. **Correr** `src/main/resources/db/outbox-events.sql` con SQLcl, SQL*Plus o el worksheet de SQL Developer
   ("Ejecutar script"). Si Hibernate ya alcanzó a crear la tabla sin identity y vacía, el script la elimina y la
   recrea. Si la tabla ya tiene identity o filas, se detiene sin tocarla.
2. **Revisar la comprobación final:** `event_sequence` con `ORDER_FLAG: Y` en `identity_options`,
   `pk_outbox_events`, `uk_outbox_events_sequence` e `idx_outbox_pending`.
3. **Desplegar y levantar requests.** Hibernate agrega solo las columnas nullable `actor_role` y `sla_business_days`.

En los tests, `src/test/resources/db/h2-outbox-events.sql` crea la misma tabla en H2 después de Hibernate, sin
`ORDER`, que H2 no acepta. Los tests no validan el `ORDER` de Oracle.

**Límites conocidos**

1. **Trámites anteriores a EP3-31:** no tienen eventos, así que report y audit parten desde el despliegue
   (EP3-06, límite 1). Un backfill con eventos marcados como reconstruidos es una mejora futura.

## Relay a Kafka (EP3-32)

`OutboxRelay` publica cada 1 s hasta 100 pendientes de `outbox_events` en `requests.events`, en orden de
`event_sequence`, con key `requestId` y `sequence` agregado al sobre. Marca `published_at` solo con la confirmación
del broker (máximo 10 s).

- **Falla de envío:** suma `attempts`, guarda `last_error` y detiene el lote.
- **Kafka confirmó pero Oracle no marcó:** detiene el lote sin sumar `attempts`. El evento se reenvía primero en el
  siguiente ciclo y el duplicado se descarta por `eventId`.
- **Kafka caído:** requests arranca y responde normal. El relay reintenta tras 1, 2, 4, 8, 16 y 30 s, y registra
  `relay-detenido` una vez por racha y `relay-reanudado` al recuperarse.

**Productor:** `acks=all`, idempotencia, `max.block.ms=5000`, `request.timeout.ms=5000`, `delivery.timeout.ms=10000`,
sin `NewTopic` y con `spring.kafka.admin.auto-create=false`. `KafkaConfig` impide arrancar si `max.block.ms` o
`delivery.timeout.ms` superan la espera de 10 s, o si `delivery.timeout.ms` es menor que `linger.ms +
request.timeout.ms`.

**Limpieza:** a las 03:30 de `America/Santiago` borra los publicados hace más de 7 días. Los pendientes no se borran.

**Health:** `/actuator/health` incluye `outbox` con `pendientes` y `antiguedadMasViejoSegundos`, sin cambiar el `UP`.
`show-details` pasa a `always`; el 8081 no es público.

**Límites conocidos**

1. **Una sola instancia:** con más, la lectura debe usar `SELECT ... FOR UPDATE SKIP LOCKED`.
2. **Duplicados posibles:** un corte o una falla de Oracle después de la confirmación reenvía el evento.
3. **Compose:** `apps/compose*.yml` aún no pasa `KAFKA_BOOTSTRAP_SERVERS` (EP3-54); sin ella los eventos esperan en el
   outbox.

## Errores y códigos (EP3-67)

`RequestsExceptionHandler` escribe todos los errores con el cuerpo de Spring Boot: `timestamp`, `status`, `error`,
`message` y `path`. Los errores del cambio de estado y de la admisión (el `GET /api/requests/{id}` previo del BFF y el
`PUT /status`) suman `code`, un identificador que no cambia aunque cambie el texto. Los consumidores deben comparar
`code`; `message` es para mostrar.

```json
{ "timestamp": "2026-11-15T03:04:55.136+00:00", "status": 403, "error": "Forbidden",
  "message": "Solo el responsable del tramite puede operarlo", "path": "/api/requests/{id}/status",
  "code": "NOT_ASSIGNEE" }
```

| HTTP | `code` | `message` |
|---|---|---|
| 400 | `USER_ID_REQUIRED` | `Falta identidad del usuario (header X-User-Id). Debe venir del BFF tras validar el JWT.` |
| 403 | `ROLE_REQUIRED` | `No tienes un rol asignado en BarrioDigital` |
| 403 | `SINGLE_ROLE_REQUIRED` | `El usuario debe tener un solo rol` |
| 403 | `STATUS_CHANGE_FORBIDDEN` | `Solo un Funcionario puede cambiar el estado` |
| 400 | `STATUS_REQUIRED` | `status es obligatorio` |
| 400 | `STATUS_INVALID` | `status invalido. Valores: INGRESADO, ADMITIDO, EN_GESTION, EN_TERRENO, RESUELTO, RECHAZADO` |
| 400 | `CANCEL_REQUIRES_CANCEL_ENDPOINT` | `La cancelacion del titular usa POST /api/requests/{id}/cancel` |
| 400 | `REJECTION_REASON_REQUIRED` | `rejectionReason es obligatorio cuando el estado es RECHAZADO` |
| 400 | `REJECTION_REASON_TOO_LONG` | `rejectionReason no puede superar 500 caracteres` |
| 400 | `INTERVENTION_REASON_REQUIRED` | `interventionReason es obligatorio para el Admin` |
| 400 | `INTERVENTION_REASON_TOO_LONG` | `interventionReason no puede superar 500 caracteres` |
| 400 | `REQUEST_BODY_INVALID` | El de Spring: `Validation failed for object='changeStatusDto'. Error count: N` o `JSON parse error: …` |
| 404 | `REQUEST_NOT_FOUND` | `Tramite no encontrado` |
| 403 | `NOT_ASSIGNEE` | `Solo el responsable del tramite puede operarlo` |
| 409 | `STATUS_TRANSITION_NOT_ALLOWED` | `No se puede pasar de {actual} a {destino}` |

- **`@Size` del body responde primero.** Un `rejectionReason` o `interventionReason` de más de 500 caracteres
  responde `REQUEST_BODY_INVALID`. `REJECTION_REASON_TOO_LONG` e `INTERVENTION_REASON_TOO_LONG` solo aparecen si el
  servicio se llama sin pasar por el controlador.
- **Mismo error, mismo código, en toda la API:** `USER_ID_REQUIRED`, `REQUEST_NOT_FOUND`, `ROLE_REQUIRED` y
  `SINGLE_ROLE_REQUIRED` (todo `/api/**` salvo `/meta/**`), y `REQUEST_BODY_INVALID` en cualquier body que no pasa
  `@Valid` o no se puede leer. `NOT_ASSIGNEE` e `INTERVENTION_REASON_*` salen también en `POST /gestiones`, que
  aplica las mismas reglas.
- **Sin `code`:** el resto de los errores conserva el mismo cuerpo, sin el campo.
- **Los códigos no repiten** los de admisión del BFF (`QUOTA_EXHAUSTED` y los demás), porque el frontend los busca en
  el mismo campo. Un código no se renombra ni se reutiliza.

`RequestErrorBodyHttpTest` compara por HTTP real cada respuesta con el cuerpo que devolvía Spring Boot antes de
EP3-67: el único campo nuevo es `code`.

## Concurrencia de estado (EP1.5-18)

EP1.5-18 protege `changeStatus()` con `findByIdForUpdate()` y bloqueo pesimista de escritura por trámite. La misma transacción lee, valida la transición, modifica y persiste, para todas las transiciones. Dos admisiones del mismo trámite no validan simultáneamente INGRESADO: una completa y la otra recibe el 409 existente al observar ADMITIDO. También se serializan admisión y rechazo; trámites distintos no usan un bloqueo global. Los errores de bloqueo/persistencia no se convierten en conflictos de negocio.

La prueba `RequestStatusConcurrencyTest` usa dos transacciones independientes y H2, siguiendo Catalog. H2 se agrega solo con alcance test y versión del BOM; no valida Oracle desplegado. Ejecutar `.\mvnw.cmd verify`. Esta protección no convierte la coordinación con Catalog en una transacción distribuida ni garantiza recuperación ante respuestas perdidas.

## Eventos del trámite (EP2-22 / EP2-23 / EP2-24 / EP3-33)

El alta confirmada en `INGRESADO` publica `certificate.receipt`. `INGRESADO → ADMITIDO` publica
`email.admitted`; `EN_TERRENO → RESUELTO` publica `email.resolved` y `certificate.resolved`.
Estos mensajes usan el exchange topic de `RabbitNamesProperties` (`cmd.topic`) y routing key igual al `type`.
`EN_GESTION → EN_TERRENO` publica `crew.ticket` en el exchange direct configurado (`cmd.direct`), con
routing key `crew.ticket`, y `email.visit` en el topic configurado, con routing key `email.visit`.
Cualquier paso a `RECHAZADO` publica `email.rejected` en el topic, con routing key `email.rejected` (EP3-33).
Las demás transiciones y las lecturas no publican eventos. Un estado repetido conserva el 409.

La publicación es asíncrona y posterior al commit, también cuando existe una transacción exterior. Rollback,
fallo de commit o transición rechazada no producen eventos. El payload incluye `requestId`, `folio`, `procedureType`,
`procedureLabel`, `address`, `solicitanteEmail` y `rejectionReason`, usando los snapshots persistidos.
`rejectionReason` solo tiene valor en `email.rejected`. `interventionReason` del Admin nunca viaja a RabbitMQ (EP3-04, sección 6.3). Un correo null se publica
igualmente y Notify lo rechaza hacia la DLQ de email, sin impedir el procesamiento del ticket de cuadrilla
o del certificado. Los certificados no requieren correo.
El productor conserva los null; Notify usa el código como respaldo de presentación de una etiqueta null.

Cada alta o cambio captura un único snapshot inmutable y registra un solo callback posterior al commit.
Cada par comparte ese snapshot, el `correlationId` del trámite y el `traceId` de la operación, que es también el del evento de
Kafka (EP3-31); cada mensaje tiene su propio
`eventId` y su instante de publicación. Los workers no reciben entidades JPA ni consultan repositorios.

Un fallo del broker o rechazo del ejecutor conserva HTTP 201 del alta o HTTP 200 del cambio ya persistido.
Un fallo de persistencia no se oculta ni se convierte en un alta exitosa.
Mensajes persistentes, mandatory y confirms aportan diagnóstico, no acreditan procesamiento ni recuperación.
RabbitMQ no usa el outbox de EP3-31, que es solo para Kafka: sin reintentos del productor, una caída del broker,
saturación o caída del proceso puede perder el comando.
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

- Recuperación durable de los comandos de RabbitMQ: el outbox de EP3-31 cubre solo los eventos de Kafka.
