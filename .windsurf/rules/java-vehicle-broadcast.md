# Task: Ensure Java STOMP server handles removed_vehicle_ids correctly (remove-first delta merge)

## Context (do not change)
We broadcast real-time vehicle updates using a delta-merge strategy. Clients MUST NOT treat incoming payloads as full snapshots. We also send explicit removals so completed trips can disappear immediately (instead of waiting for stale cleanup).

### Topic naming
STOMP topics are slash-separated and include region + vehicle type:

- /topic/{region}/vehicles/{type}
- Examples:
  - /topic/ul/vehicles/bus
  - /topic/ul/vehicles/train

### Broadcast JSON payload (from backend)
Field names are snake_case and must match exactly:

    {
      "vehicles": [ /* array of LiveVehicle */ ],
      "removed_vehicle_ids": ["vehicle_1", "vehicle_2"],
      "region": "ul",
      "vehicleType": "bus",
      "timestamp": 1730000000000
    }

Notes:
- removed_vehicle_ids is OPTIONAL and may be missing or empty.
- vehicles is OPTIONAL and may be empty.
- A message can contain only removals, only vehicles, or both.
- The server must process removals immediately.

## Required behavior (the core requirement)
Update the Java server's in-memory state (cache/store) for each (region, type) using this order:

1. REMOVE FIRST: If removed_vehicle_ids exists, remove those vehicle IDs from the cache immediately.
2. MERGE SECOND: For each entry in vehicles, upsert (insert/update) into the cache by vehicle_id.
3. CLEANUP LAST (optional fallback): Remove stale vehicles (e.g., updated_at older than 5 minutes) as a backup only. Do not rely on stale cleanup for completed trips.

This order is critical to avoid "ghost vehicles" that remain visible for minutes after completion.

## Implementation requirements

### Parsing
Parse the incoming JSON into a DTO that includes:
- List<LiveVehicle> vehicles
- List<String> removed_vehicle_ids
- String region
- String vehicleType
- Long timestamp

Be tolerant:
- Treat missing lists as empty.
- Do not throw if removed_vehicle_ids missing.
- Do not throw if vehicles missing.

### State model
Maintain cache keyed like:
- Map<String region, Map<String type, Map<String vehicle_id, LiveVehicle>>>

Or any equivalent structure that isolates region/type streams.

### Merge algorithm (pseudocode)

    void onMessage(VehicleBroadcastPayload p) {
      var cache = getCacheFor(p.region, p.vehicleType);

      // 1) REMOVE FIRST
      if (p.removed_vehicle_ids != null) {
        for (String id : p.removed_vehicle_ids) {
          cache.remove(id);
        }
      }

      // 2) MERGE SECOND (delta upsert)
      if (p.vehicles != null) {
        for (LiveVehicle v : p.vehicles) {
          if (v.vehicle_id == null) continue;
          cache.put(v.vehicle_id, v);
        }
      }

      // 3) CLEANUP LAST (fallback)
      cleanupStale(cache, Duration.ofMinutes(5));
    }

### Stale cleanup guidance
- Stale cleanup is a fallback only.
- Completed trips MUST be removed via removed_vehicle_ids ASAP.
- If updated_at is missing, decide a safe default (e.g., keep briefly, or rely on server receipt time).

## LiveVehicle expectations
At minimum, we assume:
- vehicle_id: String (unique ID, stable)
- updated_at: String or Instant (ISO timestamp recommended)

Other fields can exist (lat/lon, delay, bearing, etc.). The merge is keyed only by vehicle_id.

## Logging requirements
Add structured logs to verify correctness:
- When removals are processed: region, type, removed_count
- When vehicle updates are processed: region, type, received_count
- After merge: region, type, cache_size

Example log event:
    [STOMP] vehicles update: region=ul type=bus received=42 removed=3 cacheSize=512

## Tests / acceptance criteria
Implement at least these tests:

1) Remove-only message removes vehicle immediately
   - Given cache contains vehicle A
   - When payload has removed_vehicle_ids=[A] and vehicles=[]
   - Then cache must NOT contain A after handler

2) Remove then add in same message
   - Given cache contains vehicle A (old)
   - When payload removed_vehicle_ids=[A] and vehicles includes A (new)
   - Expected: final cache contains A with the NEW data (because remove-first then merge-second)

3) Add-only message merges without wiping others
   - Given cache contains vehicles A,B
   - When payload vehicles includes A(updated) only
   - Then cache contains A(updated), B(unchanged)

4) Null/missing fields do not crash
   - removed_vehicle_ids missing, vehicles missing
   - Handler must not throw

5) Stale cleanup does not interfere with explicit removals
   - Ensure explicit removals happen regardless of stale logic

## Do not do (common mistakes)
- DO NOT treat payload.vehicles as a full snapshot replacement (no "set cache to vehicles list").
- DO NOT process merges before removals (will cause flicker/ghosts).
- DO NOT change field name removed_vehicle_ids to camelCase in JSON.

## Output requested
1) The exact Java code changes (DTO + handler + tests) required to implement this behavior.
2) A brief explanation of why remove-first is necessary.
3) A checklist for verifying in production logs.
