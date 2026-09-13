# ms-barriodigital-requests

Microservicio de **trámites** del municipio (BarrioDigital).

Puerto: **8081**

Estados del caso: `INGRESADO → ADMITIDO → EN_GESTION → EN_TERRENO → RESUELTO | RECHAZADO`

## Cómo iniciar (con Oracle — EP1-21)

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
ORACLE_URL=jdbc:oracle:thin:@barriodig_low?TNS_ADMIN=C:/Users/alm/oracle-wallets/barriodigital
ORACLE_USER=barriodigital
ORACLE_PASSWORD=********
ORACLE_WALLET_DIR=C:/Users/alm/oracle-wallets/barriodigital
```

El `<alias>` tiene que existir en el archivo `tnsnames.ora` del wallet (`*_low`, `*_medium` o `*_high`).

3. Inicia la aplicación desde la raíz del módulo (así se carga el `.env`):

```powershell
.\mvnw.cmd spring-boot:run
```

Si la conexión funciona, en el log aparece algo como: `Oracle OK: pool conectado (...)`.  
También puedes revisar: `http://localhost:8081/actuator/health` (el componente `db` debe estar en UP).

### Dónde va el wallet

- Descárgalo desde Oracle Cloud → Autonomous Database → **Database connection** → **Download wallet**.
- Extrae el ZIP en una carpeta **fuera** de este repositorio, por ejemplo `C:\Users\<tu-usuario>\oracle-wallets\barriodigital\`.
- Ahí deben estar `tnsnames.ora`, `sqlnet.ora`, `cwallet.sso`, entre otros.
- `ORACLE_WALLET_DIR` debe apuntar a esa carpeta **real** (si la ruta no existe, la aplicación falla al iniciar con un mensaje claro).
- El alias de `ORACLE_URL` (por ejemplo `barriodig_low`) debe coincidir con un nombre de `tnsnames.ora` (abre el archivo y copia el nombre exacto).
- No subas el ZIP ni la carpeta `wallet/` a Git (están en `.gitignore`).

### Qué no va a Git

- `.env` (contraseñas)
- Wallet (`*.p12`, `*.jks`, carpeta wallet)
- `application-local.yml`

En el remoto solo queda `.env.example` con las claves vacías.

## Endpoints de prueba (aún en memoria hasta EP1-14)

El pool de Oracle ya se conecta; el CRUD todavía guarda en memoria. En EP1-14 se guarda de verdad en la base.

```powershell
curl -X POST http://localhost:8081/api/requests `
  -H "Content-Type: application/json" `
  -d "{\"title\":\"Bache en calle Los Ulmos\",\"description\":\"Hueco frente al 123\",\"procedureType\":\"bache\"}"

curl http://localhost:8081/api/requests
curl http://localhost:8081/api/requests/<id>
curl "http://localhost:8081/api/requests?status=INGRESADO"
```

## Qué sigue

- Guardar los trámites en Oracle (EP1-14)
- Que el BFF llame a requests (EP1-15)
- Cambio de estado y reglas de EN_TERRENO (EP2)
- Publicar a Rabbit al admitir un trámite (EP3)
