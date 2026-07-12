Kickball Live Sync Safe Heartbeat - Corrected Overlay

This overlay supersedes kickball-live-sync-safe-heartbeat-overlay.zip.

Fixes:
- Preserves the League Supervisor dashboard model attributes used by the current template:
  player, supervisorManagedGameWeekIds, and supervisor-prioritized game ordering.
- Publishes lifecycle changes (start/end/resume/restart/reopen) to connected managers.
- Keeps the production-safe SSE heartbeat and removes stale emitters quietly.
- Refreshes Team Manager controls when a game changes lifecycle state.

No database or Flyway changes.
