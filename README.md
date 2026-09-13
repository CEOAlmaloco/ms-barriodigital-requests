# ms-barriodigital-requests

Microservicio de **trámites** del municipio (BarrioDigital).

Puerto: **8081**

Estados del caso: `INGRESADO → ADMITIDO → EN_GESTION → EN_TERRENO → RESUELTO | RECHAZADO`

## Cómo iniciar (Oracle — EP1-21 / EP1-14)

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
| Roles | Header `X-User-Roles` (ej. `Vecino` o `Funcionario`) |

Códigos de `procedureType` (el front manda el **código**, no el label):  
`bache`, `alumbrado`, `basura`, `agua`, `ruido`, `otro`  
Catálogo: `GET /api/requests/meta/procedure-types`

Filtros de listado: `status`, `from`, `to` como **fecha** `yyyy-MM-dd` (sirve para mat-datepicker).  
Vecino: el servidor fuerza `solicitanteId = X-User-Id`. Funcionario/Admin/Auditor: ven todos.

```powershell
# Crear (como Vecino)
curl -X POST http://localhost:8081/api/requests `
  -H "Content-Type: application/json" `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino" `
  -d "{\"description\":\"Hueco frente al 123\",\"procedureType\":\"bache\",\"address\":\"Plaza Los Heroes\"}"

# Listar (fecha simple)
curl "http://localhost:8081/api/requests?status=INGRESADO&from=2026-09-01&to=2026-09-13" `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino"

# Por id
curl http://localhost:8081/api/requests/<id> `
  -H "X-User-Id: oid-de-prueba" `
  -H "X-User-Roles: Vecino"
```

Si la tabla ya tenía filas viejas sin `address`/`solicitante_id`, vaciala una vez en SQL:  
`DELETE FROM municipal_requests;` (o drop + recrear). Hibernate agrega las columnas con `ddl-auto=update`.

Colección Postman: `postman/EP1-14-requests.postman_collection.json`.

Para evidenciar en Oracle:  
`SELECT id, title, address, solicitante_id, status, created_at FROM municipal_requests ORDER BY created_at DESC;`

## Qué sigue

- Que el BFF llame a requests (EP1-15)
- Cambio de estado y reglas de EN_TERRENO (EP2)
- Publicar a Rabbit al admitir un trámite (EP3)
