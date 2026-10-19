# Architecture

## System Overview
Single-process relay between Supabase Realtime and Bussig's browser clients (`../livetrafik`). It holds one Supabase subscription per vehicle channel and fans each broadcast out to any number of STOMP subscribers, so Supabase sees one subscriber instead of one per browser. A per-(region, type) cache backs a REST snapshot endpoint. Runs as a GraalVM native binary on a Raspberry Pi 5 behind a Cloudflare Tunnel.

## Key Components

| Component | Responsibility | Key Files/Dirs |
|-----------|---------------|----------------|
| `SupabaseRealtimeService` | Phoenix-protocol client: `phx_join` per channel, 30 s `phoenix` heartbeat, reconnect 5 s after close; forwards `vehicles` broadcasts to STOMP and feeds the cache (only `{region}/vehicles/{type}` channels; any other name is relayed but not cached); latency/dispatch meters | `src/main/java/se/kumliens/livetrafik/SupabaseRealtimeService.java` |
| `VehicleCacheService` | Per-(region, type) `ConcurrentHashMap` keyed by `vehicle_id`; remove-first → upsert → stale cleanup; combined bus+train snapshot per region | `.../VehicleCacheService.java` |
| `VehicleBroadcastPayload` | Tolerant DTO for the delta payload (missing lists → empty; region/type backfilled from channel) | `.../model/VehicleBroadcastPayload.java` |
| `VehicleController` | `GET /api/latest/{region}`, `GET /api/health` (CORS `*`) | `.../VehicleController.java` |
| `WebSocketConfig` | Simple broker on `/topic` with 10 s/10 s STOMP heartbeats; client SEND rejected (subscribe-only); publish order preserved per session; endpoints `/ws` (SockJS) and `/ws-native`; allowed origins `*` | `.../config/WebSocketConfig.java` |
| `WebSocketMonitoringConfig` | Outbound channel executor 16–64 threads, queue 10 000; queue-time interceptor | `.../config/WebSocketMonitoringConfig.java` |
| `ProxyHeartbeatService` | POST status to Supabase `proxy-heartbeat` every `max(15, interval)` s with anon key and optional `x-proxy-secret` | `.../monitoring/ProxyHeartbeatService.java` |
| `WebSocketConnectionTracker` | Counts STOMP sessions from connect/disconnect events | `.../monitoring/WebSocketConnectionTracker.java` |
| `LivetrafikRuntimeHints` | Native-image reflection/resource hints | `.../config/LivetrafikRuntimeHints.java` |

## Data Flow

1. Bussig edge functions broadcast event `vehicles` on Supabase channel `{region}/vehicles/{type}` (`ul,sl` × `bus,train` by default; `supabase.channel` overrides the list).
2. `SupabaseRealtimeService.handleMessage` receives the Phoenix frame, counts it, records latency from payload `timestamp`.
3. The raw payload is sent unchanged to STOMP topic `/topic/{channel}` (1:1 with the Supabase channel name). Clients get the delta, not a snapshot.
4. For typed channels (3 slash segments) the payload is also parsed into `VehicleBroadcastPayload` and applied to `VehicleCacheService`: remove `removed_vehicle_ids`, upsert `vehicles`, drop entries older than `vehicles.cache.ttl-minutes` (default 5). 2-segment channels are forwarded only.
5. `GET /api/latest/{region}` returns the union of the region's `bus` and `train` caches.
6. Independently, `ProxyHeartbeatService` reports `connected_clients`, `supabase_connected`, `messages_relayed`, uptime and version to Supabase.

## Integration Points

| Service | Purpose | Config Location |
|---------|---------|-----------------|
| Supabase Realtime WebSocket | Upstream data | `supabase.url`, `supabase.anon-key` (`SUPABASE_ANON_KEY`, required), `supabase.regions`, `supabase.vehicle-types`, `supabase.channel` |
| Supabase `proxy-heartbeat` function | Status reporting, downtime tracking on Bussig `/admin/proxy` | `monitoring.heartbeat.url`, `.interval-seconds`, `.secret` (`HEARTBEAT_SECRET`), `monitoring.server-id` |
| Browser clients (Bussig `usePiLiveVehicles.ts`) | Downstream STOMP consumers | Public URL from Bussig `app_settings.ws_external_url` |
| Cloudflare Tunnel | Exposes :9001 publicly | On the Pi (not in repo) |

## Key Constraints

- Delta contract: remove-first order and snake_case `removed_vehicle_ids` are fixed by `.windsurf/rules/java-vehicle-broadcast.md` and the Bussig producer.
- Port 9001 is public, so everything served there is internet-facing. Actuator is limited to `health,info`. STOMP and REST have no authentication.
- Native image: types reached by reflection (Jackson DTOs) must be registered in `LivetrafikRuntimeHints`, or they fail only in the native binary.
- In-memory state only. A restart empties the cache until the next broadcasts arrive.

## Testing

- JUnit 5 via `spring-boot-starter-test`, run by Surefire in `mvn verify` (CI: `ci.yml`).
- `VehicleCacheServiceTest`: delta semantics (remove-only, remove+add, add-only, null-tolerance, stale TTL) using the package-private `Duration` constructor and `*ForTesting` accessors.
- `ProxyHeartbeatServiceTest`: request headers (secret present/absent) and payload fields, with Mockito mocks.
- `LivetrafikWsServerApplicationTests`: its nested `@Configuration` replaces the application config, so it does not load the real context.
- `SupabaseRealtimeServiceTest`: reconnect loop against a closed port (no thread growth, no reconnect after `disconnect()`).
- `StompEndpointIntegrationTest`: full context on a random port, real STOMP over `/ws-native` (heartbeat negotiation, client SEND not relayed, 2 000 deltas arrive in order). Supabase/heartbeat URLs point at `localhost:1`.
- No test covers Phoenix frame parsing or channel → topic mapping.
- Load tests (manual, against a running server): `src/test/javascript/loadtest.js` (SockJS/STOMP, Node) and `K6LoadTest.js`.
