# Technology Stack

## Languages
| Language | Version | Notes |
|----------|---------|-------|
| Java | 25 | `pom.xml` `java.version` and compiler `release`; CI uses GraalVM 25 |
| JavaScript (Node, ESM) | – | Load tests only, `src/test/javascript/` |

## Frameworks & Libraries
| Name | Version | Purpose |
|------|---------|---------|
| Spring Boot | 3.5.9 (parent) | App framework; virtual threads on (`spring.threads.virtual.enabled`) |
| spring-boot-starter-websocket | Boot-managed | STOMP over SockJS/WebSocket, simple in-memory broker |
| spring-boot-starter-web | Boot-managed | REST (`/api/*`) |
| spring-boot-starter-actuator + Micrometer | Boot-managed | `health`/`info` exposed; `trafik.*` meters registered in code |
| Java-WebSocket (`org.java-websocket`) | 1.5.4 | Client for the Supabase Realtime (Phoenix) socket |
| Jackson databind | Boot-managed | Payload parsing; vehicles kept as `ObjectNode` |
| Lombok | 1.18.42 (annotation processor) | `@Slf4j`, `@Getter`/`@Setter` |
| spring-boot-starter-test (JUnit 5, Mockito) | Boot-managed | Tests |
| `@stomp/stompjs` ^7, `sockjs-client` ^1.6, `ws` ^8 | see `src/test/javascript/package.json` | Node load-test client |

## Infrastructure
| Service | Purpose | Notes |
|---------|---------|-------|
| Raspberry Pi 5 | Production host | systemd `livetrafik-ws.service`, binary `/opt/livetrafik/trafik-websocket-server-linux-arm64`, port 9001 |
| GraalVM native image | Production artifact | `native` Maven profile, native-maven-plugin 0.10.6, AOT hints in `LivetrafikRuntimeHints` |
| Cloudflare Tunnel | Public exposure of :9001 | `trafik-ws.svante-for-the.win`; SSH for deploys at `trafik-ci-ssh.svante-for-the.win` (Access, service tokens only); interactive SSH at `trafik-ssh.svante-for-the.win` |
| GitHub Actions | CI, native ARM64 build, deploy | `.github/workflows/` (runner `ubuntu-24.04-arm` for native) |

## External Services
| Service | Purpose | Docs |
|---------|---------|------|
| Supabase Realtime | Upstream vehicle broadcasts, channels `{region}/vehicles/{type}` | `supabase.url` in `application.yml` |
| Supabase Edge Function `proxy-heartbeat` | Receives server status every 30 s | `monitoring.heartbeat.*`; contract owned by `../livetrafik` |

## Dev Tools
| Tool | Purpose | Config |
|------|---------|--------|
| Maven wrapper | Build | `.mvn/wrapper/maven-wrapper.properties` (Maven 3.9.12) |
| Node + npm | Load tests | `src/test/javascript/package.json` |
| k6 | Alternative load test | `src/test/javascript/K6LoadTest.js` |
