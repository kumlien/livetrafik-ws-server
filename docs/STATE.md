# Project State

Last Updated: 2026-10-18 07:45

## Current Phase

Phase: AndThen onboarding (brownfield mapping)
Status: On Track

## Active Stories

| Story | Owner | Status | FIS | Notes |
|-------|-------|--------|-----|-------|
| Validate `docs/requirements-discovered.md` / `docs/decisions-discovered.md` | Svante | Pending | – | Promote confirmed decisions to `DECISIONS.md` |

## Recently Completed

- **aacc0e2** deployed to the Pi (2026-10-18, build run 39100983427, deploy run 39101489316): actuator `health,info` only (metrics/env 404), client SEND dropped, STOMP heartbeats `10000,10000`, reconnect leak fix, publish order.

## Blockers

- None.

## Recent Decisions

- 2026-10-17: Public port 9001 exposes only Actuator `health,info`.
- 2026-10-17: Issue tracker is GitHub (`docs/ISSUE-TRACKER.md`).
