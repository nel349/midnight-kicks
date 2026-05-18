# Midnight Kicks

ZK-powered penalty shootout for Android. Unity 3D + Kotlin (UaaL) + a Midnight Compact contract acting as the referee.

Two players. Five rounds. Commit-reveal so neither side can cheat. Designed to ship around the **FIFA World Cup 2026** (June 11 - July 19).

This repo is a separate GitHub project (`nel349/midnight-kicks`) that consumes the Kuira Android SDK as a pre-built AAR — proving the SDK can stand on its own.

---

## Documentation map

Four documents, four concerns. Look at the one that matches what you're doing.

| Document | Use when you need… | Update cadence |
|---|---|---|
| **[`docs/PLAN.md`](docs/PLAN.md)** | Project journal — concept, target dates, architecture, **progress checklist**, SDK friction log, decision log | Per milestone |
| **[`docs/GAME_DESIGN.md`](docs/GAME_DESIGN.md)** | Specification — match state machine, batch commit-reveal protocol, contract circuits, screen IA, Unity↔Kotlin bridge JSON, edge cases | When the game rules or bridge protocol change |
| **[`docs/VISUAL_ROADMAP.md`](docs/VISUAL_ROADMAP.md)** | Plan for lifting visuals toward FC25 / FIFA quality — tiered asset drop-ins, lighting & post-FX, stadium environment, polish. The "what would it take" list | When a visual milestone closes or priorities shift |
| **[`ROADMAP.md`](ROADMAP.md)** | Visible-work checklist — Unity asset phases (environment, animation, UI, audio, ship). Frequently edited, low ceremony | Per session |

**Quick navigation:**
- *"How does the match flow?"* → GAME_DESIGN §1 (state machine)
- *"How is cheating prevented?"* → GAME_DESIGN §2 (commit-reveal)
- *"What's the contract API surface?"* → GAME_DESIGN §3 (circuits)
- *"What's left to ship?"* → PLAN (Phase tracker) + ROADMAP (Unity work)
- *"Why does the SDK do X?"* → PLAN (SDK friction log + Decision log)
- *"What would it take to look like FIFA?"* → VISUAL_ROADMAP (tiered asset/lighting/audio plan)

---

## Status snapshot

Last updated by writer of this README. Source of truth lives in PLAN and ROADMAP — check those for fine-grained state.

**Project milestones (from `docs/PLAN.md`):**
- ✅ Phase 1 — Compact contract (penalty.compact V2, 27 tests, deployed)
- ✅ Phase 2 — Midnight Android SDK (validated 2026-04-28)
- ✅ Phase 3 — Unity + Kotlin integration (replay system, MatchManager, StatePoller, PvP wait helpers)
- 🔄 Phase 4 — Full two-player game (matchmaking UI + chain logic + per-role PvP orchestrators landed; two-emulator E2E + results screen / leaderboard pending; cross-process resume pending encrypted-key persistence)
- ⏳ Phase 5 — Polish + release (Unity in separate process, uGUI choice phase + per-role visual design, QR scanner)
- ⏳ Phase 6 — Launch

**Unity asset roadmap (from `ROADMAP.md`):**
- 🔄 Phase 1 — Environment (ball ✅ goal ✅ pitch markings + stadium pending visual verify)
- ⏳ Phase 2 — Animation & cinematic (multi-camera director)
- ⏳ Phase 3 — UI (uGUI choice phase, uGUI HUD, result screen)
- ⏳ Phase 4 — Audio
- ⏳ Phase 5 — Integration & ship

---

## Project layout

```
midnight-kicks/
├── README.md                ← you are here
├── ROADMAP.md               ← Unity visible-work checklist
├── docs/
│   ├── PLAN.md              ← project journal + progress
│   ├── GAME_DESIGN.md       ← spec
│   └── VISUAL_ROADMAP.md    ← FC25-tier visual polish plan
├── app/                     ← Kotlin Android app (game logic, SDK consumer)
├── unity/                   ← Unity project (3D stadium, replay, choice UI)
├── unityLibrary/            ← Exported UaaL artifact (generated, gitignored where appropriate)
├── contract/                ← Compact contract source + tests
└── build-kicks.sh           ← End-to-end pipeline: Rust FFI → SDK AARs → Unity sync → APK
```

---

## Build

```bash
./build-kicks.sh
```

Picks up newer Unity exports from `unity/build/android-export/` automatically. To refresh Unity content: open `unity/` in Unity Editor → menu **Midnight Kicks → Export Android Library**, then run the build script. See PLAN §Architecture for the full pipeline.
