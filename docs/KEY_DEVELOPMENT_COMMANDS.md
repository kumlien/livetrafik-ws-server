# Key Development Commands

Maven needs JDK 25 on `JAVA_HOME` (the build fails with "release version 25 not supported" otherwise).

## Running the Application
| Command | Description |
|---------|-------------|
| `SUPABASE_ANON_KEY=... ./mvnw spring-boot:run` | Run on the JVM; startup fails without the key |
| `java -jar target/livetrafik-ws-server-0.0.1-SNAPSHOT.jar` | Run a packaged JAR (key in env) |
| `curl localhost:9001/api/health` · `curl localhost:9001/api/latest/ul` | Smoke checks |

Application URL: `http://localhost:9001` (STOMP: `/ws` SockJS, `/ws-native` plain WebSocket)

## Code Quality (Formatting, Linting, Type Checking)
| Command | Description |
|---------|-------------|
| `./mvnw compile` | Compile check; no formatter or linter is configured |

## Testing
| Command | Description |
|---------|-------------|
| `./mvnw clean verify` | Build + all tests (same as CI) |
| `./mvnw test -Dtest=VehicleCacheServiceTest` | One test class |
| `./mvnw test -Dtest=VehicleCacheServiceTest#methodName` | One test method |
| `cd src/test/javascript && npm install && node loadtest.js --url=http://localhost:9001/ws --clients=10 --duration=1m` | Load test (server must be running); presets `npm run test:small|medium|large` |

## Build & Deployment
| Command | Description |
|---------|-------------|
| `./mvnw clean package -DskipTests` | JAR in `target/` |
| `./mvnw -Pnative -DskipTests package` | Native binary `target/trafik-websocket-server-linux-arm64` (GraalVM 25) |
| `gh workflow run native-arm64.yml [-f create_release=true -f version=X.Y.Z]` | Build the ARM64 native binary in Actions |
| `gh workflow run native-deploy.yml` | Deploy the latest successful native artifact to the Pi and restart `livetrafik-ws.service` |
| `journalctl -fu livetrafik-ws.service` (on the Pi) | Service logs |
