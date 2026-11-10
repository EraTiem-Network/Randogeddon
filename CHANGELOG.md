# Changelog

Versions follow [Semantic Versioning](https://semver.org/).

## Unreleased

### Tracker
- Rewritten in Kotlin: a Ktor/Netty server (`:server`) follows the room over the Archipelago network protocol and
  serves the API (`/api/state`, `/api/player/{slot}`, `/api/stream` as Server-Sent Events, `/healthz`). Same behaviour
  as the former Python tracker: one tracker connection per slot, event history kept across restarts, reconnect within
  5 seconds.
- The dashboard is a Compose Multiplatform web app (`:webApp`, Kotlin/Wasm) and installable as a PWA on Android
  and iOS. Player cards, live feed, hints, player details, light and dark theme.
- The API contract is shared between both in `:shared`.
- The tracker image is built from the Gradle build; the Nexus credentials are passed in as a BuildKit secret.

### Locked worlds and people
- `World Locks` apworld: worlds that start locked until their key is found elsewhere; the generator knows about the
  locks, so seeds stay completable. Option `early_keys` to make the keys reachable from the start.
- `session.yaml` groups worlds by person; the dashboard shows each person with their worlds, their state and, for
  locked worlds, who found the key or where it was hinted. Key finds are highlighted in the live feed.
- New, more colourful dashboard design (gradient header, avatars, game chips, softer cards).
- `scripts/demo.sh`: an example session with simulated players.
- Play time per world and person, and a connection log per world (who was online when).
- Hint switches: `AP_POINT_HINTS` and `AP_AUTO_HINTS` (via `archipelago/serve.py`); the dashboard shows hint points per
  world and hides hints when both are off. Hint priority (priority / avoid / no priority) is shown.
- Darker dark theme.
- Person view: totals, worlds (opening a world shows it in place, with a back button), open hints, connection log
  and recent activity of one person.
- Hint filters: player, world, state. The live feed can be hidden and placed right (default), left or below, per viewer.

### Server
- Archipelago 0.6.8 in Docker (MultiServer), generation and deployment scripts, Traefik routes.
