# Midnight Kicks — Penalty Shootout on Midnight

**Target:** FIFA World Cup 2026 (June 11 - July 19)
**Updated:** 2026-05-27

---

## Concept

PvP penalty shootout on Android. Two players, five rounds, real stakes (PREPROD NIGHT). Unity 3D + Midnight ZK proofs. The contract is the referee — no server, no trust.

**Why:** World Cup timing. Trojan horse for ZK (players don't know they're using zero-knowledge proofs). SDK validation before Kuira ships. Open-source connector SDK as the "Build on Midnight" story.

## Game flow

1. **CHOICE** — each player picks **10 directions** (5 shoots + 5 keeps), submits ONE transaction with both arrays committed (`RegulationBatch`).
2. **PROVE** — one ZK circuit scores all 10 rounds — alternating P1 shoots / P2 shoots — comparing shooter's `shoots[i]` against keeper's `keeps[i]` per kick.
3. **REPLAY** — Unity plays the 10 rounds cinematically (stadium intro masks proof latency).

Sudden death: **one pairing per batch** (your `shoot` + your `keep`). Decisive when exactly one player scored the pairing; otherwise another pairing. Unrevealed pairings stay private (ZK property).

**Anti-cheat:** commit-reveal. Pedersen commitment of `(shoots[5], keeps[5])` + 32-byte nonce stored as private state. ZK circuit proves revealed values match commitments. Cannot change choices after commit.

> **Status:** V3 is the live spec across contract, Kotlin, and Unity (2026-05-18), and the two-player game runs end-to-end on-chain (two-emulator E2E green 2026-05-24). A 2026-05-27 pass hardened the sudden-death + rematch flow and revamped the in-match game-feel. **The game is playable; remaining work is Phase 5 polish + a pending Unity re-export, then Phase 6 launch.** Leaderboard, cloud auto-backup, and rematch re-pairing are v1.1 (post-beta).

Detailed game logic, state machine, circuit specs, UI flows, and Unity bridge spec in [`GAME_DESIGN.md`](GAME_DESIGN.md). Current Unity work and asset checklist in [`../ROADMAP.md`](../UI_ROADMAP.md).

## Stakes & gas

- PREPROD NIGHT stakes (configurable per match, default 1 NIGHT). Winner takes pot, draw = refund.
- DUST for gas via PREPROD faucet. Provider-pay model on mainnet (developer subsidizes gas).

## Timeout & disconnect

- Commitment timeout (5 min) — if opponent doesn't commit, committed player claims pot (forfeit).
- Replay is client-side (Unity) — disconnect mid-replay doesn't affect on-chain result.
- Match cancellation if opponent never joins (reclaim stake after timeout).

## Matchmaking

- **QR (in-person):** Create Match → show QR → opponent scans → joined.
- **Deep link (remote):** Create Match → share `midnight://kicks?match=<contract_address>` → opponent opens.
- Match ID = deployed contract address. No central server.

## Leaderboard

Registry contract (chosen 2026-05-24): a single long-lived contract that each match reports its result into on resolve; queryable by player address for wins/losses/draws/streaks. "Verifiable — no fake leaderboards" hinges on the registry validating the reported result against the resolved match rather than trusting an arbitrary client write (anti-cheat design open — see Phase 4).

## Architecture

- **Unity (UaaL)** — 3D stadium, ball physics, choice UI, cinematic replay. JSON bridge to Kotlin. Knows nothing about blockchain.
- **Kotlin (native)** — SDK for contract interaction, pairing (QR + deep links), UaaL bridge, state polling for opponent commits.
- **Compact contract** — match lifecycle, commit-reveal, scoring, stake escrow, payouts. Each match = new contract instance.
  - Private state (V3): `shoots[5]` + `keeps[5]` + nonce per player for regulation; one `(shoot, keep, nonce)` triple per SD pairing
  - Public ledger: participants, scores, results, winner, stakes, phase
  - Circuits: create, join, commit batch, resolve regulation, resolve sudden death, claim payout

## Repo & relationship to Kuira

Separate repo: `midnight-kicks/` (app/ + unity/ + contract/). Consumes Kuira SDK as pre-built AAR. Proves the SDK works standalone — if Kicks can't build without the full Kuira repo, the SDK isn't self-contained. Connector SDK open-sourced separately.

## Identity (two-tier) — INVESTIGATED, DECIDED

- **Tier 1 (standalone):** SDK generates keys (Android Keystore), manages UTXOs, signs/submits. No external wallet.
- **Tier 2 (Kuira enhanced):** SDK detects Kuira → delegates to TEE-backed sigil. Automatic upgrade, no code change.

| Primitive | Decision | Tier 1 (SDK) | Tier 2 (Kuira) |
|-----------|----------|-------------|----------------|
| Passkey (P-256) | CredentialManager (API 28+) | Google Password Manager | TEE/StrongBox + biometric |
| DID | `did:key` from root passkey | One per user (not per-dApp) | Sigil dashboard |
| Access key | secp256k1 (advocate P-256 to Midnight) | Self-verifiable keyAuthorization | Delegation policies |
| Recovery | PRF-encrypted cloud backup | Zero words, passkey syncs | TEE-hardened key material |

**Our edge over rvcas:** self-verifiable keyAuthorization (TEE signs directly, no server trust). Full investigation in the parent Kuira repo at `kuira-android-wallet/docs/planning/IDENTITY_INVESTIGATION.md` (not copied here — it spans both Kicks and Kuira).

---

## Progress

- [x] **Phase 1 — Compact contract**
  - [x] penalty.compact V2 (commit-reveal, batch, sudden death, timeout)
  - [x] Deploy to undeployed + 27 tests + security registry
- [x] **Phase 2 — Midnight Android SDK** (validated 2026-04-28)
  - [x] MidnightSdk facade + embedded wallet (balance + prove + submit)
  - [x] Proving key auto-download
  - [x] BBoard standalone on PREPROD (no mn serve)
  - [x] Balance progress callbacks
  - [x] Identity investigation + decisions (see IDENTITY_INVESTIGATION.md)
  - [x] Contract deployment API (each match = new contract)
  - [x] Passkey identity (CredentialManager + did:key + keyAuthorization) — verified on emulator
  - [x] PRF-encrypted cloud backup — verified on emulator (same-session round-trip, cross-device needs physical device)
- [x] **Phase 2.5 — Sigil identity + shared wallet runtime** (post-validation, 2026-05)
  - [x] PRF-derived wallet seed — the passkey *is* the wallet root; same passkey → same seed cross-app/cross-device, nothing stored
  - [x] `WalletSeedSource` extracted — one canonical seed bootstrap every dApp consumes (Kicks dropped its `TEST_SEED`)
  - [x] Path A2 — PRF-derived Ed25519 `did:key` sigil, portable across apps
  - [x] `SigilSession` — a single biometric covers sign-in + wallet bootstrap (one multi-salt PRF assertion)
  - [x] Reactive wallet recovery — the wallet unblocks automatically when the sigil arrives (no manual re-tap)
  - [x] `MidnightSdkProvider` (`sdk:wallet-runtime`) — one shared SDK process-wide; ends the duplicate indexer/zswap/dust sync between consumers, and is the teardown seam for session auto-lock (wishlist #14)
- [x] **Phase 3 — Unity + Kotlin integration** (complete 2026-05-14)
  - [x] kick-prototype with ball physics + 3 directions (URP, IL2CPP, arm64)
  - [x] UaaL export script (ExportAndroidLibrary.cs, batch mode CLI)
  - [x] Separate Gradle project (AGP 9.0.0, independent from Kuira build)
  - [x] GameController.cs — JSON bridge, 5-round choice UI, replay stub
  - [x] UnityBridge.kt — Kotlin↔Unity JSON messaging
  - [x] KicksActivity — main menu + deep link handler
  - [x] GameController receiving choicePhase + sending choicesLocked (end-to-end) — verified by 2026-05-13 logcat round-trip: Kotlin sends `choicePhase` → Unity renders 5-round picker → Unity sends `choicesLocked` → Kotlin advances match
  - [x] Replay system (5 rounds from JSON) + stadium intro cinematic — `ShotManager.PlayReplay` runs a 2.2s intro dolly (`IntroStartCam` → `EstablishingCam` + "GET READY..."), then per-round push-in to `ActionCam` peaking at ball strike, shooter run-up + kick + keeper dive + ball flight + procedural reaction (celebration hops / defeat lean), scoreboard + per-round feedback, final result hold, `replayComplete` back to Kotlin. Sudden-death replays use the same path.
  - [x] MatchManager — deploy/join/commit/reveal/claim circuit calls (state-machine refactor 2026-05-12: discrete suspend transitions, `StateFlow<MatchState>` as source of truth, `KicksActivity` is now a thin presenter over the SDK)
  - [x] StatePoller — watch opponent actions via indexer (2026-05-13: 3s poll on `MidnightConfig.queryState`, parses `penalty.compact` ledger via verified cell indices, exposed on `MatchManager.contractState: StateFlow`)
  - [x] PvP wait helpers — `MatchManager.waitForP2Committed()` / `waitForP2Revealed()` spin up the StatePoller only for the wait window (not continuously), then transition the state machine when chain state matches; `waitForP2Revealed` also reads `p2Choices` from the snapshot to build the final `MatchResult` (we never see the friend's choices locally in PvP). Unblocks Phase 4.
- [x] **Phase 4 — Full two-player game** — runs end-to-end on-chain (two-emulator E2E green 2026-05-24). Leaderboard reclassified to v1.1 (post-beta); everything required for two-player play is done.
  - [x] Onboarding (passkey → biometric → play) — sigil panel handles it, "create identity" cue shown in `SigilStatusPanel.NoneBody` covers first-launch. Tutorial overlay for "how to play" is a P5 polish item, not a blocker.
  - [x] **Matchmaking — UI scaffolding** — `CreateMatchScreen` (deploy → QR + COPY), `JoinMatchScreen` (paste/prefill + JOIN), state-based nav in `KicksActivity`, `handleDeepLink` populates `JoinMatchScreen` from `midnight://kicks?match=…`.
  - [x] **Matchmaking — chain logic** — `MatchManager.joinAsP2(address)`, `awaitOpponentJoin()`. Plumbed into both screens.
  - [x] **Create-and-go session** — no blocking auto-await on creator's device. Session persisted via `MatchStore` (encrypted), `RESUME MATCH` on menu, `CHECK STATUS` on `CreateMatchScreen` runs a short non-terminal probe.
  - [x] **PvP gameplay orchestrators** — `MatchManager.playAsP1` / `playAsP2`, P2-side `waitForP1Committed` / `waitForP1Revealed` (captures P1's shoots/keeps from chain snapshot). `KicksActivity.handleChoicesLocked` dispatches by role.
  - [x] **Contract V3 — symmetric 10-round shootout** (shipped 2026-05-17/18): each player commits `shoots[5]` + `keeps[5]` in a single regulation batch; sudden death is single-pairing per round (each player commits one `{shoot, keep}`) until decisive. Asymmetric V2 model dropped. Spec in `docs/GAME_DESIGN.md` §2; migration notes in §7. Touched contract + Kotlin + Unity:
    - Contract — `penalty.compact` V3 + 37 tests (commits `7370c7f`).
    - Kotlin — `MatchManager` V3 witnesses (`localShoots`/`localKeeps` as `Vector<5, Uint<8>>`, `localSdShoot`/`localSdKeep`), 4 phase-specific circuits (`commit/revealRegulation`, `commit/revealSuddenDeath`), 8 new `MatchState` SD variants, `MatchResult` with `p1Shoots/p1Keeps/p2Shoots/p2Keeps` + `sdRounds: List<SdRoundData>`, `ContractStateSnapshot` rewritten for the 23-cell two-group V3 layout incl. Vector decoding. `app/build.gradle.kts` now auto-syncs compiled contract artifacts to assets via `syncContractAssets` before `mergeAssets` (commit `f3d66e4`).
    - Unity — `GameController` handles dynamic-length roles arrays (10 for regulation, 2 for SD), `ShotManager.PlayReplay` already iterates `rounds.Count` so 10 regulation + N SD pairings replay without further code change. Re-exported 2026-05-18 (commits `fa7d355` C# + Kotlin 10-pick gathering, `80160bb` SD UI handoff via `getSdPicks` callback, `d322b1b` doc, `2b86272` dead V2 SD-replay path removed).
  - [x] **In-match overlay** — Compose HUD + replay + phase gates drawn over Unity (`MatchHud` / `MatchHudOverlay` / `MatchReplayOverlay`), so match status and result render natively without round-tripping to Unity (commit `e99191b`).
  - [x] **State-machine hardening** — resume-aware + idempotent transitions over a lossless `contract.ledger()` read; kills the "assume step 1 but the chain is at step N" bug class. Per-role `resumePlayAsP1/P2` walk the steps and skip whatever's already on chain (the generic version is wishlist #16).
  - [x] **Wallet seed via `WalletSeedSource`** — dropped the hardcoded `TEST_SEED`; Kicks bootstraps the real sigil-derived seed, same path as the wallet panel.
  - [x] **Cross-process resume** — `MatchStore` (encrypted per-match secret key + shoots/keeps/nonce) + `MatchStoreBackupProvider` (Block Store) + `ResumeScreen` / `ResumeRouting`; a match survives app kill and resumes from chain state.
  - [x] **Two-emulator E2E on localnet (V3)** — ran green end-to-end (verified 2026-05-24): create on emulator A, deep-link from emulator B, full regulation + sudden-death loop including the SD UI handoff. The two-player game works on-chain.
  - [x] **Results display** — the match outcome (winner / draw, per-round breakdown, final score) renders in the replay overlay via `MatchState.Resolved(MatchResult)`. No separate results screen needed.
  - [ ] **On-chain leaderboard (registry contract) — deferred to v1.1 (post-beta).** Approach decided 2026-05-24 (registry contract; verify-via-`external-contract` trust model so a client can't record a win that didn't happen). The game is fully playable without it, so it's punted past open beta. Build notes in the Leaderboard section.
- [ ] **Phase 5 — Polish + release**
  - [x] **In-match game-feel + UX pass (2026-05-27)** — the in-match surfaces are fully Compose over Unity now: animated grouped direction picker, the replay reworked so the 3D kicks play clean (live GOAL!/SAVED! + a climbing score chip) then a result/celebration end screen with a shoot-out recap + REMATCH / MENU, and the top HUD fixed for banner overlap + camera-cutout. (The live per-kick flash and the dormant LEAVE-label cleanup activate on the next Unity re-export.)
  - [x] **Sudden-death + rematch correctness (2026-05-27)** — fixed three real deadlocks/crashes surfaced by live play: the SD replay-dismissal hang (auto-advance safety net), resume from any mid-SD-round state (was crashing at `BothSdCommitted`), and a rematch reveal crash from a stale contract snapshot leaking into the new match. Indexer-down degrades gracefully (Reconnecting banner + auto-recover) instead of a silent freeze.
  - [x] **Cloud persistence — active recovery shipped, long-term tiers designed** — unfinished matches round-trip through the sigil's Block Store backup, so a match resumes across reinstall (after a manual Backup). The archive/registry + silent auto-backup tiers are designed in [`CLOUD_PERSISTENCE.md`](CLOUD_PERSISTENCE.md) (wishlist #30/#31), not yet built.
  - [x] **Drive `appDataFolder` transport proven (2026-06-02, SDK-level)** — the SDK now backs up the encrypted dust checkpoint to Google Drive's hidden per-app folder and restores it cross-device (seed-derived key, encrypt-on-device). This is the same transport the #30 archive tier will reuse, now de-risked + documented (consumer setup = a `drive.appdata` OAuth client). **Caveat that bites Kicks PvP testing:** the backup follows the device's Google account, and the passkey/sigil already binds to the GPM account — so two devices on the **same** Google account share both sigil *and* Drive folder. Cross-account is a graceful empty-folder fallback, not a crash. Ties directly to the same-account constraint in wishlist #28.
  - [ ] **Unity re-export** — activate the dormant C# accumulated this session: per-kick `roundResult` (drives the live flash/score), the removed IMGUI status/LEAVE/picker surfaces, and the LEAVE label. Owner: user's Unity toolchain.
  - [ ] APK size audit (< 100MB), proof latency tuning
  - [ ] Error handling, timeout UX, disconnect recovery
    - [x] Legible HUD failure copy — `KicksErrorCopy` maps `MatchState.Failed` throwables to plain-language lines (network/indexer, deadline, dust, funds, contract-rejected) instead of raw exceptions; raw stays in logs.
    - [x] Creator cancel-and-refund — `MatchManager.cancelMatch()` + a two-tap-confirm "Cancel match" on `CreateMatchScreen` (contract `cancelMatch`, valid in WAITING; no deadline gate). The no-opponent-joined escape hatch.
    - [x] App-kill / resume recovery — covered by Phase 4's resume-aware + idempotent transitions.
    - [ ] **In-match forfeit claim — deferred.** `MatchManager.claimForfeit()` (contract `claimTimeout`) is wired but has no UI: the 24h commit deadline makes an in-match "claim pot" impractical (claimTimeout can't fire for 24h). Needs a shorter beta deadline + a surface to live on before it's worth building.
    - [ ] Mid-tx error surfaces beyond the HUD line (per-stage failure detail).
  - [x] **Adopt `MidnightSdkProvider`** — Kicks now runs a single shared SDK. `KicksActivity` is the config authority (`ensureSdkReady` → `provider.ensureSdk(activity, WalletConfig(UNDEPLOYED))`, preserving the biometric + `SigilRequired` gate); `MatchManager` is a follower (`awaitSdk`, no own SDK build, no seed param in the production path). Same UNDEPLOYED config as the wallet panel → the provider dedups to one SDK / one indexer+zswap+dust sync, and Kicks inherits the #14 session-lock seam. Compiles + Hilt-resolves; **needs on-device E2E re-verify** (the shared-SDK match flow + instrumented `MatchManager` tests weren't run here).
  - [x] **Unity in a separate process** (`android:process=":unity"`) — **Approach A shipped** (merge `1590b1b`). Fixes the exit ANR: Unity's slow (>10s) onDestroy now blocks `:unity`'s thread, not the menu's main thread. Orchestration + SDK + biometric stay in **main**; `:unity` is renderer + Compose overlays only; `MatchBridge` (bound `Messenger` service) relays the Kotlin↔Unity bridge + `MatchHud` across the boundary, race-proofed (buffer the first `choicePhase`, re-push HUD on bind). Pause kills only `:unity` → menu survives, no ANR. Clean-exit UX: orchestrator cancelled on leave, RESUME-aware menu copy, `LEAVE` affordance. Verified on two emulators — split + both relay directions + full PvP match (commit→reveal→resolve) + ANR-free exit. Deferred: mid-wait cancel-log spot-check (easy on PREPROD's long waits); the `LEAVE` label needs a Unity re-export to show.
  - [ ] **Pause button polish** — replace `GUI.Button("II")` with a Canvas-based pause icon + proper styling.
  - [ ] **QR scanner** for matchmaking — Google Code Scanner (no camera permission needed) on `JoinMatchScreen`.
  - [ ] Play Store listing, closed beta
- [ ] **Phase 6 — Launch**
  - [ ] Open beta → announce (World Cup timing, June 11)

---

## SDK friction log

Every friction point building BBoard standalone → becomes SDK improvement.

| # | Friction | Severity | Fix |
|---|---------|----------|-----|
| 1 | Wall-clock `ctime` passed to dust spend caused error 170 (InvalidDustSpendProof) on every contract call after the first. Chain validates dust proof against `root_history.get(ctime)`, a predecessor lookup keyed by block timestamps — wall-clock returned the chain's tip-root, which never matched our locally-replayed root. | Critical | ✅ FIXED 2026-05-13 (commit `868e0d9`). `MidnightWallet.tryBalance` now uses `blockInfo.timestamp` (already fetched for ledgerParameters). Mirrors TS wallet's `currentTime ?? blockData.timestamp`. |
| 2 | DustLocalState serialize/deserialize corrupts Merkle roots | High | In-memory only workaround. Needs SCALE codec fix. |
| 3 | Full dust sync 60s on PREPROD (253k events) | Medium | Background sync + progress bar. Optimize later. |
| 4 | `fromId: null` skips early events (indexer treats null ≠ 0) | Critical | Always pass `id: 0`. |
| 5 | Tag-prefix hex splitting corrupts events at scale | Critical | Line-per-event file format. |
| 6 | No progress during balance+submit (60s opaque) | High | BalanceProgress callbacks (6 stages). |
| 7 | FFI pointer write-back corrupts cached state | High | Don't write back post-spend state. |
| 8 | WebSocket backpressure OOM on 250k events | Medium | File streaming, Rust native memory. |
| 9 | No contract deployment API | Medium | ✅ FIXED. `MidnightContract.deploy()` + FFI. |
| 10 | Content behind system status bar | Low | WindowInsets padding. |
| 11 | **16 KB page-size compliance** — Android 15+ devices use a 16 KB memory page size (a Play requirement for apps targeting Android 15+); native `.so` LOAD segments aligned to the old 4 KB boundary trip the "ELF alignment check failed" warning and run in slower page-size-compat mode. Surfaced on a 16 KB emulator running Kicks. The dialog flags many libs across three owners. | High (pre-Play, not pre-testnet) | **Ours — ✅ FIXED:** `libkuira_crypto_ffi.so` now links with `-Wl,-z,max-page-size=16384` (CMakeLists.txt); LOAD segments verified at 0x4000. **Unity (Kicks-owned) — TODO:** `libunity/libil2cpp/libmain/lib_burst_generated/libgame` need a 16 KB-aligned re-export (Unity Player setting + rebuild). **Third-party deps — TODO:** `libquickjs.so` (`com.dokar.quickjs`), `libandroidx.graphics.path.so`, `libdatastore_shared_counter.so`, `libc++_shared.so` (NDK) — clear as each ships a 16 KB-aligned release / NDK r28+ link. Non-blocking for testnet (runs in compat mode); must be clean before Play submission. |

---

## SDK connector — wishlist (non-blockers)

The SDK wishlist now lives at [Kuira SDK Roadmap](https://kuiralabs.github.io/kuira-sdk-android/roadmap/)
(source: `kuira-sdk-android/docs/roadmap.md`). Patterns surfaced by Kicks
go there now — that's where the next dApp learns from this one.


---

## Decision log

| Decision | Choice | Why |
|----------|--------|-----|
| Batch vs per-round | Batch commit, 1 tx per phase | V3 packs `shoots[5]` + `keeps[5]` into one regulation commit; sudden death batches a single `{shoot, keep}` pair per round. 2 txs per regulation (commit + reveal) and 2 per SD round, vs 20+ for fully per-round. Cinematic replay relies on the bundled list. |
| Symmetric vs asymmetric roles | V3 = symmetric (each player shoots 5 + keeps 5) | V2 was asymmetric (P1: 3 shots, P2: 2 shots) — doesn't match real penalty rules. V3 redesign aligns with real-life shootouts and lets players strategize offense and defense independently. |
| Sudden death | Single-pairing per round; SD round increments until exactly one player scores | Mirrors real shootouts (one pair at a time after regulation draw). Unrevealed pairings stay private (ZK). No infinite loops in practice — random play converges, equal-skill play is bounded by player patience. |
| Unity vs Compose | Unity (UaaL) | 3D stadium, ball physics, cameras. |
| Standalone repo | Separate from Kuira | Tests SDK boundaries. Separate release. |
| Pairing | QR + deep links (built in Kicks) | Simpler than connector transports. |
| Key curve | secp256k1 (advocate P-256) | Midnight accepts secp256k1 today. |
| keyAuthorization | Self-verifiable (TEE signs) | No server trust needed. |
| DID | One per user from root passkey | Sigil = one identity. |
| Recovery | PRF-encrypted cloud backup | Zero words. Passkey syncs → biometric → restored. |
| Gas | PREPROD faucet (provider-pay on mainnet) | Lowest friction for testnet. |
| Proving | On-device (local) | Proves hardware capability. No server. |
