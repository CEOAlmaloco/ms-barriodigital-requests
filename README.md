# ms-barriodigital-requests

Microservicio de **trámites** del municipio (BarrioDigital).

Puerto: **8081**

Estados del caso: `INGRESADO → ADMITIDO → EN_GESTION → EN_TERRENO → RESUELTO | RECHAZADO`

## Arranque

```powershell
.\mvnw.cmd spring-boot:run
```

## Endpoints mínimos (primer commit)

Persistencia **en memoria** para poder probar ya. Oracle cloud viene en EP1-21 / EP1-14.

```powershell
# Crear tramite
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

## Secretos (EP1-19 / EP1-21)

`.gitignore` ignora `target/`, `.env`, wallets y `application-local.yml`.  
Copiá `.env.example` → `.env` en local; **nunca** subas passwords ni el wallet.

## Qué sigue

- Conectar Oracle sin secretos en git (EP1-21)
- Persistir de verdad (EP1-14)
- Cambio de estado / reglas EN_TERRENO (EP2)
- Publicar a Rabbit al admitir (EP3)
