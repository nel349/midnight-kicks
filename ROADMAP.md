# Midnight Kicks — Roadmap

ZK-powered penalty shootout. Android UaaL. URP, mobile-friendly stylized art.

## Done
- [x] Rigged X Bot characters in scene (Shooter, Keeper)
- [x] Mixamo Soccer Game Pack imported, Humanoid retargeting working
- [x] AnimatorControllers wired (Idle, Kick, DiveLeft, DiveRight, JumpCenter)
- [x] ShotManager orchestrates run-up → kick → dive → score
- [x] GameController bridge (choicePhase, replay, suddenDeathReplay, status)
- [x] BallKicker physics + Keeper.cs dive (side-tilt fake-animation hook)
- [x] Midnight lighting & post-FX baseline
- [x] Migrated into midnight-kicks repo

## Phase 1 — Environment
- [x] **Real soccer ball** — 0.22m regulation, not a primitive sphere
- [x] **Real goal + net** — FIFA proportions (7.32×2.44m); update keeper/ball offsets to match
- [ ] **Grass pitch with painted markings** — penalty box, spot, arc
- [ ] **Stadium backdrop + floodlights** — fills the void, sells "midnight"

## Phase 2 — Animation & cinematic
- [ ] **Verify dive directions** — left vs right may need Mirror; goal/save/miss reaction states
- [ ] **Multi-camera director** — wide, behind-shooter, kick close-up, behind-keeper
- [ ] **Lighting refinement** — night skybox, floodlight pools, color grading

## Phase 3 — UI
- [ ] **uGUI choice phase** — replace IMGUI direction buttons
- [ ] **uGUI HUD** — score, round counter, GOAL/SAVE punch
- [ ] **Result screen** — winner, round summary, return-to-Kotlin

## Phase 4 — Audio
- [ ] **Core SFX** — kick, net, post, catch, whistle, crowd cheer/groan
- [ ] **Mix + ambient loop** — AudioMixer groups, crowd murmur

## Phase 5 — Integration & ship
- [ ] **Bridge protocol verification** — Editor mocks for every message type
- [ ] **Android build + on-device test** — UaaL export, real device flow
- [ ] **Performance + APK size** — IL2CPP, ASTC, light probes, shader stripping
- [ ] **Final polish pass** — bugs, timing, screenshots

## Cross-cutting (touch at any session)
- Code: extract magic numbers into a tuning ScriptableObject; replace runtime `Find*` with serialized refs
- Git: one session → one branch → one merge; never commit half-broken state
- Verify: press `T` (mock replay hotkey) after every change

## Important concepts to preserve
- **+Z is forward in Unity** — shooter faces +Z toward goal, keeper faces -Z toward shooter
- **Tripo origins are baked at body center** (Y≈0.5 needed); **Mixamo origins are at feet** (Y=0)
- **Humanoid retargeting** is the bridge between Mixamo clips and our character avatar — both sides need Rig→Humanoid + Avatar
- **Static GLB prefabs cannot accept bone animation** — only the rigged X Bot can
- **Bridge is JSON over UnitySendMessage / AndroidJavaClass** — Kotlin owns game logic, Unity is render/animation slave
- **Editor `.unity` and `.prefab` files are YAML** — hand-edits are possible but every prefab GUID swap requires re-anchoring fileIDs (see `unity-study-notes.md`)
