# Decisions

<!-- Unvalidated candidates: docs/decisions-discovered.md. Promote entries here once confirmed. -->

## Current ADRs

| ID | Title | Status | Scope |
|----|-------|--------|-------|

## Superseded

| Prior Decision | Superseded By | Notes |
|----------------|---------------|-------|

## Still Current

- **Actuator exposure**: port 9001 exposes only `health,info` because it is public via the Cloudflare Tunnel (2026-10-17, `2e2049d`).

- **Subscribe-only clients**: `WebSocketConfig.RejectClientSendInterceptor` drops every client STOMP SEND (counter `trafik.stomp.inbound.rejected`); the simple broker would otherwise relay client frames on `/topic/**` to all subscribers (2026-10-17).

## Pending
