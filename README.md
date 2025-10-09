# VoxelMind

A cross-loader (Fabric + NeoForge via Architectury) client mod that connects a multimodal AI agent to Minecraft. It streams periodic screenshots to an OpenAI‑compatible endpoint and applies the returned structured JSON actions.

Status (Oct 2025)
- Core observe loop stable.
- CONTROL (autonomous takeover) is **experimental / incomplete**: movement + basic navigation + mouse actions work, but reliability, richer pathfinding, advanced targeting, safety limits, and action smoothing are still WIP.
- Use at your own risk; keep a finger on the keybind to switch back to DISABLED.

TL;DR Features (current)
- AI Modes: DISABLED / OBSERVE / CONTROL (experimental)
- Structured action schema: chat, navigation (relative offsets), mouse (tap/hold/release), target hints (auto camera lock), basic path BFS
- Periodic screenshot capture (PNG) + world context → OpenAI compatible `chat/completions`
- Keybinds: P cycle modes, O reload config
- Simple conversation memory & chat rate limiting / dedupe
- Multi‑version build tasks producing matrix jars (1.21 → 1.21.8)

Quick Start
```zsh
./gradlew build          # Build default version (see gradle.properties)
./gradlew :fabric:runClient
# or
./gradlew :neoforge:runClient
```
Multi‑version jars:
```zsh
./gradlew buildAll          # All supported 1.21.x
./gradlew buildFor_1_21_6   # Single version pattern: buildFor_<underscored_version>
```
Artifacts land under:
- `multi-version-artifacts/fabric/`
- `multi-version-artifacts/neoforge/`

Modes
- DISABLED: loop off (no screenshots / API calls)
- OBSERVE: agent runs, parses actions, only chat is applied (no player control)
- CONTROL (experimental): attempts full control (movement, navigation, mouse, auto target lock)

Keybind Flow
Press P repeatedly: DISABLED → OBSERVE → CONTROL → …  (chat feedback: `[VoxelMind] AI Mode: <MODE>`)
Press O: reload `voxelmind.json`.

Selected Commands
- `/vm enable|observe|disable|status|now`
- `/vm say <text>` – inject a user message & trigger a decision
- Conversation: `/vm conv show|clear|limit get|set <n>`
- Targeting radius: `/vm lockradius get|set <r>`
- Debug toggle: `/vm debug on|off|status`
- Chat tuning (`/vm chat ...`): `dedup`, `mininterval`, `autoreply`, `answeronly`, `loosereply`

Configuration (created on first run)
File: `config/voxelmind.json` (dev paths: `fabric/run/...` or `neoforge/run/...`)
Essential fields:
```json
{
  "agent_url": "https://your-endpoint/v1/chat/completions",
  "api_key": "",
  "model": "gpt-4o-mini",
  "decision_interval_ticks": 5
}
```
Other notable fields: `target_lock_radius`, `assist_only_primary_when_aiming`, `assist_primary_reach_distance`, `allow_public_chat`, `show_ai_prefix`, `debug`.

How Requests Work (brief)
- System prompt enforces single JSON object output (temperature 0, `response_format: json_object`).
- User message includes: minimal world context + base64 screenshot (image_url) + recent chat buffer.
- Expected assistant JSON keys (any optional): `chat`, `navigation`, `mouse`, `target`.

Action Schema Snapshot
```json
{
  "chat": {"toAll": "hi", "toSelf": "debug info"},
  "navigation": {"dx": 3, "dy": 0, "dz": -2},
  "mouse": {"left": "HOLD", "right": "TAP"},
  "target": {"entity": "zombie"}
}
```
(Fields omitted = no change.) OBSERVE ignores control fields; CONTROL applies them.

Current Limitations / Caveats
- CONTROL pathfinding: simple BFS, shallow vertical handling only.
- No global safety / cooldown budget yet (model could spam actions within interval granularity).
- Target lock heuristic radius; may pick unintended entities/blocks.
- No server‑side authoritative checks (client only).
- Full camera trajectory smoothing & multi-step tool use not implemented.

Roadmap (short)
- Harden CONTROL (safety caps, smoother motion, better targeting)
- Richer navigation (stairs, ladders, fluids, multi-layer elevation)
- Tool / inventory awareness & action sequencing
- Optional headless inference throttling / cost metrics
- More model backends & local adapter hook

Tips
- Increase `decision_interval_ticks` if you hit rate or cost limits.
- Keep debug on while tuning prompts; turn off for normal play.
- If CONTROL misbehaves: press P to exit, or edit config and reload (O).

Key Source Files
- `AIAgentController` – mode loop
- `HttpAgentClient` – request / response handling
- `ActionSchema` – action model
- `PathNavigator` – basic BFS path steps

License
GPL-3.0

Credits / Disclaimer
This is an experimental research mod. Expect breaking changes and rough edges while CONTROL matures.
