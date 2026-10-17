# Decisions
> Status: Discovered – requires validation by team

Inferred from code, config, workflows and git history on 2026-10-17 (HEAD `2e2049d`). Rationales marked "inferred" are not stated anywhere in the repo.

## Current ADRs

None in tree. No `docs/adr/` or decision records exist. `.windsurf/rules/java-vehicle-broadcast.md` is the closest thing, a binding spec for remove-first delta handling.

## Superseded

- **JAR on OpenJDK 17 under systemd unit `trafik-ws`** → native arm64 binary under `livetrafik-ws.service`, Java 25 – Evidence: `README.md:284-343` (stale) vs `pom.xml:30,103-129`, `native-deploy.yml:65-74`, `.agent_temp/andthen-init-brief.md:24-25`
- **Dash-named channels `vehicle-updates-{region}-{type}`** → slash-named `{region}/vehicles/{type}` (commit `b3cea82`). A legacy dash parser remains – Evidence: `SupabaseRealtimeService.java:280-283,319-324`, `README.md:75,99`
- **Combined/global feeds `/topic/vehicles-{region}` and `/topic/vehicles`** → per-type topics. Kept only as documentation and a 2-segment "combined feed" branch that skips caching – Evidence: `README.md:391-392`, `SupabaseRealtimeService.java:238-241`
- **Full-snapshot payloads** → deltas with `removed_vehicle_ids` and remove-first merge (commit `b3cea82`) – Evidence: `.windsurf/rules/java-vehicle-broadcast.md:4,132`, `VehicleCacheService.java:44-67`
- **Actuator exposure `*`** → `health,info` (commit `2e2049d`). Rationale: port 9001 is public via Cloudflare Tunnel. Not yet deployed – Evidence: `application.yml:25-26`, `.agent_temp/andthen-init-brief.md:52-56`
- **Unauthenticated heartbeat** → heartbeat carries `x-proxy-secret` (commit `f53f074`). Rationale: let Supabase reject spoofed heartbeats – Evidence: `ProxyHeartbeatService.java:110-113`, `ProxyHeartbeatServiceTest.java:34`
- **`deploy.yml` as deploy path** → `native-deploy.yml`. `deploy.yml` has failed on every run (download-artifact@v4 cannot see other runs' artifacts) but is still in tree – Evidence: `.github/workflows/deploy.yml:25-29`, Actions history

## Still Current

- **Java-WebSocket 1.5.4 for the Supabase side, not Spring's `WebSocketClient`** – inferred: small anonymous-subclass callback API and raw Phoenix frames without a STOMP/Spring client stack. Since commit `130023a` – Evidence: `pom.xml:50-54`, `SupabaseRealtimeService.java:113-139`
- **Hand-rolled Phoenix protocol, not a Supabase Java SDK** – join and heartbeat frames are string templates, `vsn=1.0.0` – Evidence: `SupabaseRealtimeService.java:107,151-164,177-184`
- **Spring in-process simple broker, no external broker (RabbitMQ/ActiveMQ relay)** – inferred: single Pi instance, no horizontal scaling, no persistence needed – Evidence: `WebSocketConfig.java:20`
- **Both SockJS `/ws` and plain `/ws-native` endpoints** – SockJS gives fallback transports and also serves raw WebSocket at `/ws/websocket`, which Bussig uses. `/ws-native` has no known consumer – Evidence: `WebSocketConfig.java:26-31`, `.agent_temp/andthen-init-brief.md:37`
- **STOMP topic = Supabase channel name** (`/topic/` + channel), a 1:1 mapping with no translation table – Evidence: `SupabaseRealtimeService.java:229`
- **Raw payload passthrough to STOMP; cache used only for REST snapshot** – forward the upstream JSON node before and independent of cache processing, so clients get exactly what Supabase sent and must do their own delta merge – Evidence: `SupabaseRealtimeService.java:228-235`
- **Remove-first, merge-second, stale-cleanup-last order** – rationale stated: avoid ghost vehicles after trip completion; stale cleanup is fallback only – Evidence: `VehicleCacheService.java:61-64`, `.windsurf/rules/java-vehicle-broadcast.md:31-38,85-88`, `VehicleCacheServiceTest.java:45-62,98-109`
- **Per-(region, type) `ConcurrentHashMap<CacheKey, VehicleState>` with inner `ConcurrentHashMap<vehicle_id, StoredVehicle>`** – isolates streams per the windsurf rule. Vehicles are stored as Jackson `ObjectNode`, not a typed `LiveVehicle` – Evidence: `VehicleCacheService.java:30,123-131,208`, `.windsurf/rules/java-vehicle-broadcast.md:55-59`
- **5 min stale TTL, configurable via `vehicles.cache.ttl-minutes`** – Evidence: `VehicleCacheService.java:35`, `.windsurf/rules/java-vehicle-broadcast.md:36`
- **REST snapshot hardcodes `bus` + `train`** – inferred: matches the two types in use at the time. Not driven by `supabase.vehicle-types` – Evidence: `VehicleCacheService.java:82-83`
- **Virtual threads enabled** (commit `89ebf4b`) – stated rationale: reduce thread pressure on the Pi – Evidence: `application.yml:4-6`, `README.md:90`
- **GraalVM native image with explicit `RuntimeHints`** – inferred: startup time and memory on a Pi 5. Hints cover `VehicleBroadcastPayload` reflection (Jackson `convertValue`) and `build-info.properties` – Evidence: `pom.xml:103-129`, `LivetrafikRuntimeHints.java:11-22`, commit `94d9363`
- **Outbound STOMP executor 16 core / 64 max / 10 000 queue** (commit `b2a59ac`, "tune WebSocket thread pool") – inferred: fan-out headroom for ~200 clients. Effectively 16 threads until the queue fills – Evidence: `WebSocketMonitoringConfig.java:32-37`
- **Heartbeat to Supabase edge function `proxy-heartbeat` every 30 s with anon key + shared secret `x-proxy-secret`** – lets Bussig's `/admin/proxy` track uptime and downtime without polling the Pi – Evidence: `ProxyHeartbeatService.java:61-115`, `MonitoringProperties.java:19-24`, `.agent_temp/andthen-init-brief.md:38`
- **CORS `*` on REST and allowed origin patterns `*` on both STOMP endpoints** – inferred: browser clients come from Bussig on arbitrary hosts (preview/prod) – Evidence: `VehicleController.java:17`, `WebSocketConfig.java:27,31`
- **No authentication on STOMP, SockJS or REST** – inferred: data is public vehicle positions. Side effect: clients can also SEND to `/topic/*` (see requirements Gap 1) – Evidence: absence of Spring Security in `pom.xml:33-70` and of inbound interceptors in `src/main`
- **Public exposure through Cloudflare Tunnel, not an open router port** – Evidence: `README.md:345-380`, `.agent_temp/andthen-init-brief.md:27`
- **Deploy via manual `native-arm64.yml` → `native-deploy.yml` over Cloudflare Access SSH with an interactive login step** – Evidence: `.github/workflows/native-arm64.yml:3-50`, `.github/workflows/native-deploy.yml:3-74`; 12 successful deploy runs, last 2026-02-09
- **Fixed 5 s reconnect, no backoff, no attempt cap** – inferred: simplicity. Supabase outages produce a reconnect every 5 s indefinitely – Evidence: `SupabaseRealtimeService.java:266-271`
- **New `ScheduledExecutorService` per reconnect and per `onOpen`** – not a deliberate decision. Causes a thread leak (requirements Gap 2) – Evidence: `SupabaseRealtimeService.java:174,267`
- **Lombok for `@Slf4j`, `@Getter/@Setter`** (commit `89ebf4b`), version pinned to 1.18.42 in the compiler annotation path, compile scope – Evidence: `pom.xml:60-64,91-97`, `MonitoringProperties.java:8-9`, `VehicleBroadcastPayload.java:17-18`
- **Micrometer timers with percentile histograms for every hop** (Supabase latency, dispatch, outbound queue) – Evidence: `SupabaseRealtimeService.java:83-98`, `WebSocketMonitoringConfig.java:47-57`, commit `b2a59ac`
- **Configuration via `@Value` with inline defaults, and `@ConfigurationProperties` only for `monitoring.*`** – two styles coexist – Evidence: `SupabaseRealtimeService.java:50-63`, `MonitoringProperties.java:10`

## Pending

- **Deploy path for the 2026-10-12 binary.** No `native-deploy.yml` run follows that day's 08:04 UTC build, yet the Pi binary dates from 10:11 local. Is there a path outside the workflows? Should `deploy.yml` be deleted?
- **When `2e2049d` (Actuator `health,info`) gets deployed.** Until then production exposes 13 endpoints, including `loggers` POST.
- **Whether to block client SEND to `/topic/**`** (inbound interceptor or Spring Security messaging).
- **Whether to enable `preservePublishOrder`** given delta semantics.
- **Reconnect strategy.** Fix the executor leak, and decide on backoff and channel-level rejoin.
- **Fate of legacy surfaces.** Dash-name channel parsing, the 2-segment combined feed, `/ws-native`, the `/app` prefix, `release.yml` JAR releases, `K6LoadTest.js`.
- **Whether regions stay `ul,sl`** or become `ul` only, to match Bussig.
- **Source of heartbeat `version`.** `APP_VERSION` env or `build-info`.
- **Whether to add a native compile to CI.**
- **README rewrite** for native/`livetrafik-ws.service`.
