# Project Learnings

## Build & Native Image

- **JDK 25 required** – `./mvnw` fails with "release version 25 not supported" on an older `JAVA_HOME`; sdkman has `25.0.3-tem` / `25.0.1-graalce` locally.
- **Native-only failures** – Jackson/reflection types missing from `LivetrafikRuntimeHints` pass every JVM test and break only on the Pi.
- **No `@Lazy` injection** – its runtime JDK proxy lacks reflection metadata; the native binary dies at startup (`MissingReflectionRegistrationError`). Build native locally (`25.0.1-graalce`) after config changes.

## STOMP / Supabase

- **Broker heartbeats need a scheduler** – `setHeartbeatValue` without `setTaskScheduler` fails startup; without both, CONNECTED says 0,0 (`StompEndpointIntegrationTest` guards it).
- **Failed channel joins are silent** – `phx_reply` errors log at DEBUG; `supabase_connected=true` can coexist with zero relayed messages.
- **Heartbeat secret is mandatory upstream** – without `HEARTBEAT_SECRET`, `proxy-heartbeat` answers 401 every 30 s (WARN only) and `/admin/proxy` shows the proxy offline while relaying works.

## Testing

- **Context test loads nothing real** – `LivetrafikWsServerApplicationTests` has a nested `@Configuration`, which replaces app config; use `@TestConfiguration` for a real context check.

## Error Patterns

| Error | Type | Conclusion |
|-------|------|------------|
| `release version 25 not supported` | Deterministic | Set `JAVA_HOME` to a JDK 25 |

## Process & Tooling

- **Deploy workflow** – use `native-deploy.yml`; `deploy.yml` always fails (`download-artifact@v4` without run-id sees only its own run's artifacts).
- **Deploy SSH uses a service token** – the workflows reach the Pi through `trafik-ci-ssh.svante-for-the.win`, whose Access policy admits only service tokens (`CF_ACCESS_CLIENT_ID`/`SECRET`, read by `cloudflared` as `TUNNEL_SERVICE_TOKEN_*`). A token missing from that policy fails with an Access 403, not an SSH error. The host key is pinned via `PI_KNOWN_HOSTS`. Agents in auto mode are blocked from dispatching the deploy, so the user runs `! gh workflow run native-deploy.yml`.
- **Deploy takes the latest green build** – `native-deploy.yml` downloads the newest successful `native-arm64.yml` artifact, not a chosen run. Dispatch it only after the build is green, or the Pi gets the previous binary.
- **Pi access is browser-only** (Raspberry Pi Connect) – hand the user read-only commands; hash secrets with `sha256sum`, never print them.
