# VoxelMind

An Architectury-based mod (Fabric + NeoForge) that connects a multimodal AI agent to Minecraft. The agent consumes live screenshots and returns structured actions to control (or just observe) the player.

Status: initial scaffold with a stub agent and full client wiring.

What’s implemented
- Three AI modes: DISABLED (off), OBSERVE (screenshots + model decisions + chat only, no player control), CONTROL (full autonomous control + chat)
- Common action schema: chat (public/self), navigation, mouse left/right (tap/hold/release) plus target locking (camera view auto-managed)
- Screenshot capture (async GPU readback) -> PNG bytes
- Agent controller loop (≈4 Hz default, configurable) capturing screenshots & calling the agent in OBSERVE or CONTROL mode
- Keybind: P cycles modes (DISABLED -> OBSERVE -> CONTROL -> ...), O reloads config
- Chat to self via client-only messages; chat to all via normal chat send
- Fabric and NeoForge wiring through Architectury EnvExecutor

Quick start
- Build (default Minecraft version 1.21.6 from `gradle.properties`):

```zsh
./gradlew build
```

  (Skip tests if needed — none yet):

```zsh
./gradlew build -x test
```

- Run Fabric dev client:

```zsh
./gradlew :fabric:runClient
```

- Run NeoForge dev client:

```zsh
./gradlew :neoforge:runClient
```

Multi-version build matrix (produces jars under `multi-version-artifacts/<mcVersion>/{fabric,neoforge}`)
- Sequential build ALL supported versions (1.21 → 1.21.8):

```zsh
./gradlew buildAll
```

- Single specific version (example 1.21.6):

```zsh
./gradlew buildFor_1_21_6
```

  Other tasks follow the pattern: `buildFor_1_21`, `buildFor_1_21_1`, ..., `buildFor_1_21_8`.

Notes on multi-version tasks:
- Each `buildFor_*` invokes a clean isolated build with the correct dependency versions.
- Output runtime jars (sources/dev jars excluded) are copied & renamed with a `+mc<version>` suffix if not already present.

In-game
- Press P repeatedly to cycle AI Mode: DISABLED -> OBSERVE -> CONTROL. Chat displays: `[VoxelMind] AI Mode: <MODE>`.
  - DISABLED: Agent loop off, no screenshots, no control.
  - OBSERVE: Agent loop on (screenshots + decisions) and can send chat, but DOES NOT move/aim/click.
  - CONTROL: Full autonomous control (movement, aiming, path navigation, mouse actions, chat).
- Press O to reload `voxelmind.json` at runtime.
- With the stub agent, you’ll see periodic `[AI] StubAgent tick ...` messages in OBSERVE or CONTROL mode.

Commands (current)
- Core mode & status:
  - `/vm enable` → CONTROL mode
  - `/vm observe` → OBSERVE mode
  - `/vm disable` → DISABLED
  - `/vm status` → show mode + debug flags
  - `/vm now` → force an immediate decision (no-op if DISABLED)
- Interaction with AI:
  - `/vm say <text>` → send a user message to the AI & trigger a decision
- Conversation buffer:
  - `/vm conv show [lines]` → show last N (default 20) conversation lines
  - `/vm conv clear` → clear stored conversation context
  - `/vm conv limit get|set <n>` → view or change max stored lines (4–200)
- Targeting:
  - `/vm lockradius get|set <value>` (1–128) → radius for target auto-lock logic
- Debug:
  - `/vm debug on|off|status`
- Chat behavior tuning (`/vm chat ...`):
  - `dedup get|set <ticks>` → duplicate suppression window (20–20000)
  - `mininterval get|set <ticks>` → minimum ticks between AI chat (0–2000)
  - `autoreply on|off|status` → auto-reply toggle in observe modes
  - `answeronly on|off|status` → only respond when explicitly addressed
  - `loosereply on|off|status` → relaxed matching for being addressed

Deprecated / removed
- `/vm goal <text>` and `/vm clear` have been removed (previous goal system replaced by direct conversation context + user prompts).

Configuration (OpenAI-compatible only)
- A JSON config file is created on first run at: `config/voxelmind.json` (per Minecraft instance)
  - In dev runs this typically resolves to:
    - Fabric: `fabric/run/config/voxelmind.json`
    - NeoForge: `neoforge/run/config/voxelmind.json`
- Fields:
  - `agent_url`: Your OpenAI-compatible chat.completions endpoint
  - `api_key`: Bearer token; sent as `Authorization: Bearer <api_key>`
  - `model`: Model name (e.g., `gpt-4o-mini`, `gpt-4o`, `qwen-vl-plus`)
  - `decision_interval_ticks`: How often the agent runs (ticks). Default 5 (≈4 Hz). Applies in OBSERVE & CONTROL.
  - `target_lock_radius`: Search radius (blocks) for auto target locking (CONTROL only).
  - `assist_only_primary_when_aiming`: If true, only permit primary action when crosshair on target/log within reach (CONTROL only).
  - `assist_primary_reach_distance`: Max distance (blocks) for the above rule.
  - `allow_public_chat`: If true, AI chat messages go to public chat; otherwise client-only.
  - `show_ai_prefix`: Prepend `[AI] ` to messages.
  - `debug`: Extra logging.

Examples (OpenAI)

```json
{
  "agent_url": "",
  "api_key": "",
  "model": "gpt-4o-mini",
  "decision_interval_ticks": 5
}
```

How it talks to your model
- The HTTP agent uses an OpenAI-compatible `chat/completions` call:
  - `messages` include a `system` instruction enforcing JSON-only output
  - The `user` message contains world context + base64 PNG screenshot (image_url)
  - `response_format: {"type":"json_object"}`, `temperature: 0`
- Assistant’s single JSON object: `chat`, `navigation`, `mouse`, `target` keys.

Action schema (model output)
- Chat: `{"chat": {"toAll": "hello", "toSelf": "debug info"}}`
- Navigation (3D): `{"navigation": {"dx": 5, "dy": 1, "dz": -3}}`
- Mouse: `{"mouse": {"left": "HOLD", "right": "TAP"}}`
- Target (one strategy): coordinate / block id / block tag / entity type / entity name substring
- Camera: model no longer outputs view; mod auto-locks onto target (CONTROL only)

Modes & control application
- OBSERVE: navigation / target / mouse outputs are parsed but ignored for player control (only chat processed).
- CONTROL: full application (movement, path navigation, target lock, mouse actions, conditional primary assist logic).
- DISABLED: loop inactive (no screenshots, no API calls).

Key files
- `AIAgentController`: handles tri-state mode & loop
- `ActionSchema`: structured action types
- `HttpAgentClient`: HTTP + prompt & parsing
- `PathNavigator`: BFS + step generation

Notes
- Adjust `decision_interval_ticks` for responsiveness vs. cost.
- New navigation request cancels previous path.
- `target_lock_radius` affects search during CONTROL only.
- Primary action assist gating only active in CONTROL.

Navigation & pathfinding
- BFS with limited vertical support (single-block ascents/descents). Larger elevation changes are future work.

Localization
- Keybind strings under `assets/voxelmind/lang/en_us.json` and `zh_cn.json`.

License
- GPL-3.0

Debug Logging
When `debug` is true:
- `[VoxelMind][AI raw]` truncated original model JSON (first ~1000 chars)
- `[VoxelMind][AI parsed]` normalized parsed summary
Disable in production to reduce noise.
