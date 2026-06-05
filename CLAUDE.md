# Gods of Death — Guild Raid Monitor (BE)

Spring Boot 3.3.0 REST API per monitorare le raid di una gilda in Tacticus. Gira sia come app standalone che come AWS Lambda.

## Stack tecnico

- **Java 17**, Spring Boot 3.3.0 (Jakarta EE)
- **AWS DynamoDB** via SDK v2 + enhanced client
- **JWT** autenticazione stateless (JJWT 0.12.6)
- **Discord OAuth2** per login
- **MapStruct + Lombok** per mapping e riduzione boilerplate
- **SpringDoc / OpenAPI 3.0** per Swagger UI
- **AWS Lambda** via `aws-serverless-java-container-springboot3`

## Avvio locale

```bash
# Con profilo dev (DynamoDB locale o endpoint override)
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Build per Lambda
mvn package -Plambda
```

Porta default: **8080**. Swagger UI: `http://localhost:8080/swagger-ui.html`

## Struttura package

```
com.godsofdeath.monitor
├── config/          DynamoDB, Security, Swagger
├── controller/      AuthController, RaidController, GlobalExceptionHandler
├── service/         AuthService, DiscordService, RaidService
├── security/        JwtAuthFilter, JwtUtil
├── document/        Entità DynamoDB (@DynamoDbBean)
├── repository/      Accesso dati DynamoDB (scan/query custom)
├── mapper/          MapStruct: Document → DTO
└── dto/
    ├── input/       Payload in ingresso
    ├── internal/    DTO interni (es. risposta Discord)
    └── output/      Risposte API (sempre wrap in GenericResponseDTO)
```

## API endpoints

| Metodo | Path | Auth | Descrizione |
|--------|------|------|-------------|
| POST | `/api/auth/login` | No | Login con nome Discord → JWT |
| POST | `/api/auth/discord/callback` | No | OAuth2 code exchange → JWT |
| GET | `/api/raid/current-season` | JWT | Stats raid stagione corrente |

## Modello risposta

Tutte le risposte usano `GenericResponseDTO<T>`:

```java
{ "status": "OK" | "KO" | "DENIED", "message": "...", "data": {...} }
```

Factory methods: `GenericResponseDTO.ok(msg, data)`, `.ko(msg)`, `.denied(msg)`

## Tabelle DynamoDB

| Tabella | PK | Descrizione |
|---------|-----|-------------|
| `sp_anag_players` | `USER_ID` | Anagrafica giocatori (apiKey, discordName, enabled) |
| `sp_boss_lookup` | `UNIT_ID` | Nome boss per unitId Tacticus |
| `sys_config` | `name` | Configurazione di sistema (key-value) |

## Configurazione

### Variabili d'ambiente (Lambda/prod)
- `JWT_SECRET` — secret per firma JWT
- `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`, `DISCORD_REDIRECT_URI`
- `AWS_DYNAMODB_ENDPOINT` — vuoto per AWS reale, `http://localhost:8000` per DynamoDB locale

### Profili Spring
- `dev` — log DEBUG
- `lambda` — disabilita Swagger, usa IAM role per DynamoDB (no access-key hardcoded), context-path `/god-player-monitor`

> **ATTENZIONE:** `application.yml` contiene credenziali AWS e JWT secret hardcoded — solo per sviluppo locale. In prod tutto viene da env vars nel profilo `lambda`.

## Sicurezza

- JWT firmato HMAC-SHA256, scadenza 1 ora
- Claims JWT: `user_id`, `user_game_name`
- CORS: tutti gli origin permessi (da rivedere per prod)
- CSRF disabilitato (stateless)
- Route pubbliche: `/api/auth/**`, `/swagger-ui/**`, `/v3/api-docs/**`

## Business logic chiave

**`RaidService`** — la logica più complessa:
1. Chiama l'API Tacticus con l'`API_KEY` del giocatore
2. Aggrega i danni per boss e per giocatore su tutti i raid della stagione
3. Calcola la media della gilda per boss
4. Calcola indicatori di performance per giocatore (above/below/at average)
5. Restituisce `CurrentSeasonDataDTO` con struttura annidata: Season → BossGroups → Encounters

**`BossLookupRepository`** — fallback substring matching:
- Prima cerca exact match per `UNIT_ID`
- Se non trovato, fa scan con `contains` per gestire varianti del nome

**`PlayerRepository`** — case-insensitive scan su `DISCORD_NAME` per il login

## Deployment AWS

`template.yaml` (SAM):
- Runtime: Java 17, Memory: 1024 MB, Timeout: 30s
- Handler: `StreamLambdaHandler::handleRequest`
- Policy: CRUD su tutte e 3 le tabelle DynamoDB
- API Gateway proxy routes: `/` e `/{proxy+}`

## Pattern da rispettare

- Ogni nuovo endpoint → DTO dedicato in `input/` e `output/`
- Risposta sempre wrappata in `GenericResponseDTO`
- Eccezioni gestite centralmente in `GlobalExceptionHandler`
- Nuovi accessi DynamoDB → nuovo metodo nel repository dedicato, non logica inline nel service
- Usa `@Value` per config, mai hardcoded nei service
