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
- Build all: 

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

In-game
- Press P repeatedly to cycle AI Mode: DISABLED -> OBSERVE -> CONTROL. Chat displays: `[VoxelMind] AI Mode: <MODE>`.
  - DISABLED: Agent loop off, no screenshots, no control.
  - OBSERVE: Agent loop on (screenshots + decisions) and can send chat, but DOES NOT move/aim/click.
  - CONTROL: Full autonomous control (movement, aiming, path navigation, mouse actions, chat).
- Press O to reload `voxelmind.json` at runtime.
- With the stub agent, you’ll see periodic `[AI] StubAgent tick ...` messages in OBSERVE or CONTROL mode.

Commands (excerpt)
- `/vm enable` – switch to CONTROL mode.
- `/vm observe` – switch to OBSERVE (passive) mode.
- `/vm disable` – switch to DISABLED.
- `/vm status` – shows current Mode, Goal, and (on Fabric) Debug.
- `/vm goal <text>` – set user goal and automatically enters CONTROL mode.
- `/vm clear` – clear user goal.
- `/vm now` – force an immediate decision (ignored if DISABLED).
- `/vm lockradius get|set <value>` – manage target lock radius (1–128).
- Debug subcommands unchanged: `/vm debug on|off|status`.

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

Examples
- OpenAI:

```json
{
  "agent_url": "https://api.openai.com/v1/chat/completions",
  "api_key": "sk-***",
  "model": "gpt-4o-mini",
  "decision_interval_ticks": 5
}
```

- Qwen (DashScope compatible endpoint):

```json
{
  "agent_url": "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
  "api_key": "sk-***",
  "model": "qwen-vl-plus",
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
