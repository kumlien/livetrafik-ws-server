# Product

## Vision
A WebSocket relay for Bussig (`../livetrafik`), which shows live bus and train positions. Bussig's browsers subscribe to this relay instead of to Supabase Realtime, so Supabase serves one subscriber regardless of audience size.

## Target Users

- **Bussig browser clients**: receive vehicle deltas per region and vehicle type over STOMP with low latency.
- **Bussig operator** (admin page `/admin/proxy`): sees whether the proxy is up, connected to Supabase, and how many clients it serves.

## Value Propositions

- Supabase Realtime subscriber count and cost stay flat as the audience grows.
- Topic and payload stay the same as on Supabase, so clients can switch between the proxy and direct Supabase via Bussig `app_settings.ws_source`.

## Key Capabilities

| Capability | Description | Status |
|------------|-------------|--------|
| Realtime relay | Supabase `{region}/vehicles/{type}` → STOMP `/topic/{region}/vehicles/{type}` | Shipped |
| Snapshot API | `GET /api/latest/{region}` from the delta-merged cache | Shipped |
| Health reporting | Heartbeat to Supabase `proxy-heartbeat` every 30 s | Shipped |

## Non-Goals

- Transforming or filtering vehicle data. Payloads are relayed unchanged.
- Client authentication. The feed is public data.
- Persistence. State is in memory and rebuilt from live broadcasts.

## Success Metrics

- _TODO: confirm with owner, e.g. relay latency p99 (`trafik.supabase.payload.latency`), uptime per `proxy_server_status`._
