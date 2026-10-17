# Project State

Last Updated: 2026-10-17 20:22

## Current Phase

Phase: AndThen onboarding (brownfield mapping)
Status: On Track

## Active Stories

| Story | Owner | Status | FIS | Notes |
|-------|-------|--------|-----|-------|
| Validate `docs/requirements-discovered.md` / `docs/decisions-discovered.md` | Svante | Pending | – | Promote confirmed decisions to `DECISIONS.md` |

## Recently Completed

- **2e2049d** (2026-10-17): Actuator exposure limited to `health,info`.

## Blockers

- Not deployed: actuator fix `2e2049d`, STOMP SEND block `43a3001`, reconnect leak fix `cadb38b`, STOMP heartbeats `5f745ba`, publish order. Run `native-arm64.yml` then `native-deploy.yml`, and verify that `https://trafik-ws.svante-for-the.win/actuator` lists only `health` and `info`.

## Recent Decisions

- 2026-10-17: Public port 9001 exposes only Actuator `health,info`.
- 2026-10-17: Issue tracker is GitHub (`docs/ISSUE-TRACKER.md`).
