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

Alta en estado `INGRESADO`. Listado con filtros opcionales `status`, `from`, `to` (ISO-8601).

```powershell
# Crear
curl -X POST http://localhost:8081/api/requests `
  -H "Content-Type: application/json" `
  -d "{\"title\":\"Bache en calle Los Ulmos\",\"description\":\"Hueco frente al 123\",\"procedureType\":\"bache\"}"

# Listar
curl http://localhost:8081/api/requests

# Por id (reemplaza el UUID)
curl http://localhost:8081/api/requests/<id>

# Filtro por estado
curl "http://localhost:8081/api/requests?status=INGRESADO"
```

Colección Postman: `postman/EP1-14-requests.postman_collection.json`.

Para evidenciar en Oracle Cloud: Database Actions → SQL →  
`SELECT id, title, status, created_at FROM municipal_requests ORDER BY created_at DESC;`

## Qué sigue

- Que el BFF llame a requests (EP1-15)
- Cambio de estado y reglas de EN_TERRENO (EP2)
- Publicar a Rabbit al admitir un trámite (EP3)
