# Midnight Kicks — Penalty Shootout on Midnight

**Target:** FIFA World Cup 2026 (June 11 - July 19)
**Updated:** 2026-05-18

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

> **Status:** V3 is the live spec across contract, Kotlin, and Unity (2026-05-18). The on-chain contract, the Android orchestrator, and the C# choice/replay flow all use the symmetric 10-round model with single-pairing sudden death. Remaining Phase 4 milestone: two-emulator E2E on localnet against the V3 build.

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

On-chain, indexer-queryable. Wins/losses/draws/streaks per player address. Verifiable — no fake leaderboards.

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
- [ ] **Phase 4 — Full two-player game**
  - [x] Onboarding (passkey → biometric → play) — sigil panel handles it, "create identity" cue shown in `SigilStatusPanel.NoneBody` covers first-launch. Tutorial overlay for "how to play" is a P5 polish item, not a blocker.
  - [x] **Matchmaking — UI scaffolding** — `CreateMatchScreen` (deploy → QR + COPY), `JoinMatchScreen` (paste/prefill + JOIN), state-based nav in `KicksActivity`, `handleDeepLink` populates `JoinMatchScreen` from `midnight://kicks?match=…`.
  - [x] **Matchmaking — chain logic** — `MatchManager.joinAsP2(address)`, `awaitOpponentJoin()`. Plumbed into both screens.
  - [x] **Create-and-go session** — no blocking auto-await on creator's device. Session persisted via `KicksSessionStore` (SharedPrefs), `RESUME MATCH` on menu, `CHECK STATUS` on `CreateMatchScreen` runs a short non-terminal probe. Cross-process resume needs encrypted key persistence (next).
  - [x] **PvP gameplay orchestrators** — `MatchManager.playAsP1` / `playAsP2`, P2-side `waitForP1Committed` / `waitForP1Revealed` (captures P1's shoots/keeps from chain snapshot). `KicksActivity.handleChoicesLocked` dispatches by role.
  - [x] **Contract V3 — symmetric 10-round shootout** (shipped 2026-05-17/18): each player commits `shoots[5]` + `keeps[5]` in a single regulation batch; sudden death is single-pairing per round (each player commits one `{shoot, keep}`) until decisive. Asymmetric V2 model dropped. Spec in `docs/GAME_DESIGN.md` §2; migration notes in §7. Touched contract + Kotlin + Unity:
    - Contract — `penalty.compact` V3 + 37 tests (commits `7370c7f`).
    - Kotlin — `MatchManager` V3 witnesses (`localShoots`/`localKeeps` as `Vector<5, Uint<8>>`, `localSdShoot`/`localSdKeep`), 4 phase-specific circuits (`commit/revealRegulation`, `commit/revealSuddenDeath`), 8 new `MatchState` SD variants, `MatchResult` with `p1Shoots/p1Keeps/p2Shoots/p2Keeps` + `sdRounds: List<SdRoundData>`, `ContractStateSnapshot` rewritten for the 23-cell two-group V3 layout incl. Vector decoding. `app/build.gradle.kts` now auto-syncs compiled contract artifacts to assets via `syncContractAssets` before `mergeAssets` (commit `f3d66e4`).
    - Unity — `GameController` handles dynamic-length roles arrays (10 for regulation, 2 for SD), `ShotManager.PlayReplay` already iterates `rounds.Count` so 10 regulation + N SD pairings replay without further code change. Re-exported 2026-05-18 (commits `fa7d355` C# + Kotlin 10-pick gathering, `80160bb` SD UI handoff via `getSdPicks` callback, `d322b1b` doc, `2b86272` dead V2 SD-replay path removed).
  - [ ] Two-emulator E2E on localnet (V3) — create on emulator A, deep-link from emulator B via `adb shell am start -a android.intent.action.VIEW -d "midnight://kicks?match=<addr>"`. Unity has been re-exported with V3 C#; ready to test end-to-end including SD UI handoff.
  - [ ] **Cross-process resume** — encrypted persistence of per-match secret keys + choices/nonces so resume survives app kill. Shares the data shape with Block Store cross-device sync; doing them together makes sense. (PLAN.md SDK connector wishlist #4.)
  - [ ] Results screen + leaderboard query
- [ ] **Phase 5 — Polish + release**
  - [ ] APK size audit (< 100MB), proof latency tuning
  - [ ] Error handling, timeout UX, disconnect recovery
  - [ ] **Unity in a separate process** (`android:process=":unity"`) — eliminates the shared-main-thread ANR seen when Unity's onDestroy takes >10s on emulator. Requires re-plumbing `UnityBridge` across processes (AIDL/IPC) since static-field passing breaks. Current workaround: `RequestPause` kills the process to bypass Unity teardown.
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

---

## SDK connector — wishlist (non-blockers)

These don't block Kicks shipping, but Kicks is forcing patterns that the
Kuira connector SDK should bake in so the next dApp doesn't reinvent
them. Add to this list whenever a MatchManager-style workaround appears.

| # | Wish | Why it'd help | Today's workaround |
|---|------|---------------|--------------------|
| 1 | `awaitIndexerSynced(blockHeight)` primitive | Replace fixed `delay(5000)` waits between deploy → first call and between sequential tx. Reduces total match time and makes flow deterministic. | Hard-coded 3–8s sleeps in MatchManager (INDEXER_SETTLE_MS, POST_JOIN_SETTLE_MS). |
| 2 | `MidnightContract.stateFlow(): Flow<ContractState>` | StatePoller becomes a one-liner. Lets UI react to opponent actions without polling boilerplate. | Caller writes a polling loop against the indexer GraphQL. |
| 3 | Retry policy on `contract.call(...)` | "Indexer says contract not found, wait + retry" is a near-universal pattern after deploy. Bake retry-with-backoff into the call surface. | MatchManager.aiJoin loops 10× by hand, matching on `"not found"` substring. |
| 4 | Serializable state snapshot for BlockStore backup | A `MatchState` snapshot (address + nonces + commit/reveal flags) should round-trip to bytes for Google BlockStore so a player can resume after process death or device hop. | Manual serialization in the app layer. |
| 5 | First-class `Player` / `Identity` abstraction | The two-player witness pattern (P1 secret key, P2 secret key, swap on each call) is a recurring shape. A `Player` with `secretKey` + `coinPublicKey` + witness-registration helper would shrink each call site. | MatchManager passes raw `ByteArray` secrets through every helper. |
| 6 | Auto force-resync of dust around contract calls | Every tx after a deploy or another tx needs `wallet.forceResyncDust()` to see the new UTXO state. Should happen inside `contract.call` when needed. | Manual `forceResyncDust()` between every tx in MatchManager. |
| 7 | Deadline / timeout helper | `BigInteger.valueOf(System.currentTimeMillis() / 1000 + N)` for unix-second deadlines is everywhere a circuit takes a deadline. | Computed by hand in MatchManager.aiJoin. |
| 8 | "Test-mode seed" path | The shared test seed (`TEST_SEED` in KicksActivity) for fast iteration on faucet networks should be a single SDK opt-in, not a literal in every example app. | Hex literal in KicksActivity. |
| 9a | ✅ **DONE — Lossless ledger reads via SDK (`MidnightContract.ledger()`)** | Promoted from wishlist to friction log + landed in 2026-05-20. The cell-hex parsing path turned out to be fundamentally lossy: `MidnightConfig.queryState` exposes a flattened JSON cell tree where `Vector<N, Uint<8>>` cells with zero elements lose positional info (each Uint<8>=0 serializes to a zero-length byte array, so the cell hex is a concatenation of only the non-zero elements). No dApp can recover the original arrays from this view. **Fix:** the SDK now offers `MidnightContract.ledger()` which spins up QuickJs, invokes the contract's own generated `ledger(state)` function, and marshals the typed return values back to Kotlin via [`MidnightLedger`](../../../core/compact-engine/src/main/kotlin/com/midnight/kuira/core/compact/MidnightLedger.kt) — the same lossless path the contract uses internally. Each typed accessor (`getUint8`, `getUint64`, `getBoolean`, `getBytes`, `getVectorUint8`, + `OrNull` variants) carries range/shape validation; missing fields throw `MissingLedgerFieldException` with the known-fields list, type mismatches throw `WrongLedgerFieldTypeException` — loud failures beat the silent-zeros the cell-hex parser produced. Kicks's `ContractStateSnapshot` and BBoard's `BBoardRepository` migrate to `contract.ledger()` and drop their bespoke parsers. | Lossy cell-hex parsing in each dApp (`ContractStateSnapshot.kt`, `BBoardRepository.kt`), which silently misdecoded any `Vector<N, Uint<8>>` containing zero elements. |
| 9b | Typed `Ledger<T>` codegen from `.compact` | With #9a in place, the runtime decoding is lossless and shared. The remaining ergonomic gap is compile-time type safety: today consumers write `ledger.getUint8("p1Score")` instead of `ledger.p1Score`. A Gradle plugin (`com.midnight.kuira.contract`) that reads the compiled JS's `ledger()` descriptor and emits a typed Kotlin data class per contract (`PenaltyLedger(val p1Shoots: IntArray, val p1Score: Int, ...)`) would close it. Lower urgency now that the correctness problem is solved — defer until a second consumer (e.g. CipherDefense) needs it. | String-keyed accessors with runtime field-name validation. Works correctly, just less ergonomic than a generated data class. |
| 10 | Public block subscription | `MidnightSdk.indexerClient` is `private`, so app code can't reach `subscribeToBlocks()` for push-based contract-state watching. Expose either the indexer or a thin `MidnightContract.stateFlow()` over it. | `StatePoller` falls back to 3s polling. |
| 11 | Kuira Gradle plugin for contract artifact sync | Copying compiled `.compact` outputs into Android assets (`penalty-contract.js` + `keys/*.{prover,verifier,bzkir}`) is the same shape across every Kuira dApp, but each one hand-rolls a Gradle Copy task with hardcoded contract names and paths. A `com.midnight.kuira.contract` plugin should let dApps declare `kuiraContract { source = "contract/src/managed/penalty" }` (or auto-discover from a sibling module) and get sync-before-`mergeAssets` for free. | `app/build.gradle.kts` registers a hand-rolled `syncContractAssets` Copy task with the contract path and circuit-key globs baked in. |
| 12 | Typed witness factories for non-scalar types | `WitnessResult(null, ByteArray)` works for `Bytes<32>` / `Uint<8>`, but for `Vector<N, T>` and other compound types the developer has to know the runtime's serialization (concatenated element bytes) and assemble it by hand. SDK should expose factory helpers like `vectorWitness(length=5) { intArrayOf(L, C, R, L, R) }` and `boolWitness { true }` that produce the right `ByteArray` for the underlying compact-runtime descriptor. | MatchManager packs Vector witnesses by hand: `byteArrayOf(*shoots.map(Int::toByte).toByteArray())`. |
| 13 | Compactc version pinning | Compactc's `--runtime-version` must match the project's `@midnight-ntwrk/compact-runtime` npm version, but there's no in-repo way to declare which compactc version to use — developers find out via a runtime error after `npm run compact` writes incompatible bytecode. Either a `.compact-version` file in `contract/` honored by the `compact` wrapper, or auto-pinning where `compact compile` reads package.json's pinned runtime version and switches itself. | Kicks V3 requires compactc 0.30.0 (runtime 0.15.0); 0.31.0 is the user default and emits incompatible 0.16.0 bytecode. Workaround: invoke `~/.compact/versions/0.30.0/aarch64-darwin/compactc` directly or run `compact update 0.30.0`. |
| 14 | **🔴 CRITICAL — Session auto-lock for the wallet SDK** | The decrypted seed lives in the `MidnightSdk` instance cached on `WalletPanelViewModel` for the entire process lifetime. After one biometric on first SDK build, every subsequent value-bearing call (register, send, deploy, Kicks join/commit/reveal, payout claim) runs with **zero re-authentication**. The Keystore's 30s validity window is bypassed by SDK caching — we never re-call `loadSeed`, so Keystore is never re-consulted. A borrowed-while-foregrounded device drains funds in one tap. SDK needs three independent re-lock triggers: idle timeout (default 5–15 min), background timeout (re-lock on resume after N seconds backgrounded), and screen-lock observer (re-lock when device locks). Plus a manual "Lock now" affordance in the wallet panel. See `docs/security/SECURITY_NOTES.md` 2026-05-19 entry for the threat-model analysis. | None. The cached SDK and its in-memory seed persist until `onCleared()` or process death. No idle eviction, no foreground re-arm, no manual lock. |
| 15 | **🔴 CRITICAL — Sigil UX safety (no destructive-button-on-Error trap, no missing sign-out)** | The `SigilStatusPanel` has two compounding UX gaps that together make a sigil look deleted with one wrong tap. (a) When `restoreSeed` fails (e.g. user taps Restore on a Forged sigil but there's no cloud backup, or PRF fails, or any other `BackupException`), the catch block sets status to `Error` and `ErrorBody` renders a **forge sigil** button. `forgeSigil` → `persistSigil` unconditionally overwrites the existing prefs. One tap from "I just wanted to try restore" to "the sigil I had is gone." (b) There is no in-app **sign out** affordance from `Forged` state — the only way to leave a sigil is force-uninstall the app, so users in distress end up tapping any visible action including the forge-in-Error path. Required fixes: (1) `signOut()` on `SigilPanelViewModel` that wipes `sigil_identity` prefs + optionally deletes Block Store blob, status → `None`, biometric-gated; (2) sign-out button in `ForgedBody` under a "danger zone" divider with a confirmation modal; (3) `restoreSeed`'s catch must distinguish "restore failed from None" (Error with forge button is fine — nothing to lose) from "restore failed from Forged" (return to `Forged` with a transient banner, never show forge); (4) `ErrorBody`'s forge button only renders when previous state was `None` / `BackupAvailable`. Observed live on 2026-05-19: dev re-deployed the app via Android Studio Apply Changes (wiped `/data/data/.../shared_prefs/`), user tapped Restore expecting recovery, restore correctly failed (no backup ever existed), Error UI showed forge as the remediation. The dev path that wiped prefs was IDE-only, but the UX problem is real for prod users who lose their session by other means. | Force-quit + restart the app to reload prefs (if they exist), or accept the visual deletion. No in-app safety. |
| 16 | **Resume-aware multi-step protocol orchestrator** | `MatchManager` is ~600 lines of state-machine + transitions + resume logic that's ~90% generic. Three bugs landed on 2026-05-20 — `joinAsP2` precondition fail when state was already `Deployed`, `awaitOpponentJoin` precondition fail when state was already `Joined`, `MatchReady.CONTINUE` launching Unity's choice picker on a session where picks were already on chain — all reduce to "code path assumes I'm at step 1 but state is at step N." The fix in each case was the same shape: walk the steps and skip whichever ones are already reflected on chain. `resumePlayAsP1` / `resumePlayAsP2` are hand-rolled versions of this per role. SDK should provide a `Protocol<S>` DSL where each step declares (a) precondition on a typed [`MidnightLedger`](../../../core/compact-engine/src/main/kotlin/com/midnight/kuira/core/compact/MidnightLedger.kt), (b) the action, (c) an "already done on ledger?" predicate. `protocol.runFrom(contract.ledger())` would auto-skip steps the chain already shows landed and resume from the right point. Folds in #1 (settle waits become declarative timeouts on each step) and #6 (force-resync becomes a between-step concern). | Per-dApp orchestrator + per-dApp `resumePlayAsP*` helpers + per-dApp precondition catches. Three live bugs in this session, all the same shape. |
| 17 | **`MidnightContract.callIdempotent(circuit, args, isDoneOnLedger)`** | Closely related to #16, but applicable outside a full multi-step protocol. Every action we wrote on 2026-05-20 had to add a catch-block for "the contract already reflects this transition" — `joinAsP2` matches on the `"WAITING phase"` substring; `awaitOpponentJoin` had to learn to no-op when state was past `Deployed`. Each dApp re-invents the same `try { call } catch { if (chain says it's done) ok }` pattern, and missing it means a wasted tx + a `Failed` state on resume. SDK could ship `contract.callIdempotent(circuit, args, isDoneOnLedger = { ledger -> ledger.getUint8("phase") > PHASE_WAITING })`. The SDK reads the ledger first; if the predicate is true, returns synthetically without a chain call. Eliminates the per-dApp catch pattern that's now appeared in three places. | Hand-rolled catch + substring-match pattern in `joinAsP2`, `awaitOpponentJoin`'s new idempotent shortcut, and (implicitly) `submitP1Picks`/`submitP2Picks` whenever resume drives those. |
| 18 | **`WitnessStore` — encrypted, multi-slot witness persistence** | `MatchStore` is ~250 lines per dApp: `EncryptedSharedPreferences` setup, role-aware rehydration through `rehydrateLocalIdentity`, content-equality boilerplate on `ByteArray` fields (the same trap `ContractStateSnapshot` had — see #9a), Block Store cloud backup wiring through `MatchStoreBackupProvider`. The whole rehydrate-witnesses-after-process-death pattern is generic but every dApp implements it. Without this layer the resume orchestrator (#16) can't actually work — it needs the witnesses to drive the remaining steps. SDK should ship a `WitnessStore(scope: String)` keyed by `(contractAddress, slotName)`, with PRF-encrypted persistence + Block Store backup baked in. dApp code becomes `store.save(address, "p1Regulation", witnessBundle)` / `store.load(address, "p1Regulation")`. Composes with #16: each `Step` declares the witness slot it owns; resume auto-rehydrates before walking. | Per-dApp `MatchStore` + `MatchStoreBackupProvider` + manual `rehydrateLocalIdentity` for each role + content-equality boilerplate per witness type. |
| 19 | **`MidnightContract.readOnly(...)` factory** | We made `coinPublicKey: ByteArray?` nullable on 2026-05-20 to support polling contracts that don't have a wallet attached. But the read-only pattern still requires cargo: skip witness registration, omit cpk, remember not to call `.call()` until runtime. A future dApp author won't know that's a valid shape from reading the builder. A dedicated `MidnightContract.readOnly(config) { contractJs = …; address = … }` factory that returns a typed `MidnightContractReadOnly` interface with `ledger()` + `stateFlow()` but NO `call`/`prepare`/`deploy` would make the misuse compile-time impossible instead of surfacing as `IllegalStateException` at the first write call. | Today's pattern: `MidnightContract.create { ... }` with implicit cpk-null sentinel + `requireWriteCapable()` runtime check inside every write method. |
| 20 | **`MidnightContract.observeLedger(): Flow<MidnightLedger>`** | Sibling of #10 — the SDK already has the infrastructure (private indexer subscription), what's missing is the dApp-facing shape. Today every dApp's `StatePoller` is a 3-second polling loop calling `contract.ledger()` + `distinctUntilChanged`. A push-based `Flow<MidnightLedger>` backed by `subscribeToBlocks` would (a) drop the per-dApp poller class entirely, (b) cut detection latency from ~3s to ≤ one block (~6s on PREPROD but ≤ 1 block deterministically), and (c) compose with #16: the protocol orchestrator subscribes to the flow and reacts to phase changes instead of awaiting fixed predicates with hand-tuned timeouts. | `StatePoller` 3s loop in Kicks; once #10 lands without the typed-Flow shape, every dApp will still write that loop. |
| 21 | **`compact-engine-testing` artifact — `FakeMidnightContract` for state-machine unit tests** | While writing the 2026-05-20 precondition tests for `resumePlayAsP1`/`resumePlayAsP2`, the third bespoke test seam landed in `TestableMatchManager`: `executeDeploy`, `executeJoinMatch`, now `readResumeSnapshot`. Pattern is now obvious — every dApp will write the same test scaffolding. A published `compact-engine-testing` artifact shipping `FakeMidnightContract` (canned `MidnightLedger` snapshots, deferred-await knobs for "opponent commits now", invocation counters per circuit) would let dApp authors unit-test their state machines without spinning up QuickJs or the real chain. Folds into #16's `Protocol` DSL once it exists: the fake feeds canned ledger snapshots, the protocol walks the steps, the test asserts which steps fired. | Three hand-rolled `internal open suspend fun` seams in `MatchManager`, plus the `TestableMatchManager` subclass per-dApp. |
| 22 | **🔴 CRITICAL — Open-SDK permissionless integration (drop default `PasskeyConfig`)** | `core:identity:IdentityModule.providePasskeyConfig()` hardcodes `rpId = "nel349.github.io"` + `rpName = "Kuira"` as the default Hilt binding. Any third-party dApp that consumes the SDK and forgets to override that provider silently lands on the maintainer's domain — and PRF then doesn't work for them unless the maintainer adds their package + cert SHA-256 to the maintainer-hosted `assetlinks.json`. That coupling means the SDK is effectively permissioned: every new dApp requires a PR to the maintainer's `nel349.github.io` repo. The post-PRF architecture is supposed to be permissionless (any dApp picks its own domain → hosts its own `assetlinks.json` → uses `:sdk:wallet-seed` and gets its own sigil-derived wallet with zero maintainer touch), but the default provider undermines that by giving wrong-but-working semantics to a misconfigured host. Required: (1) remove `providePasskeyConfig` from `IdentityModule` — host-provided with no fallback, so Hilt fails fast with a clear missing-binding error instead of silently routing through someone else's domain; (2) each of our example apps (BBoard, Kicks) ships its own ~5-line `*IdentityModule` providing its own `PasskeyConfig` (also gives per-app `rpName` branding in the passkey prompt — "Midnight Kicks" vs "Kuira"); (3) `INTEGRATION.md` at the SDK root showing third-parties the 4-step recipe (domain → assetlinks → Hilt module → maven deps). Cost: ~5 files (1 main repo, 1 per example, 1 doc). Removes the last "Kuira owns the authority" coupling and makes the SDK genuinely open. | Default `PasskeyConfig` lives in `IdentityModule` pointing at `nel349.github.io`; new third-party dApp authors don't know they have to override it; if they don't, PRF fails until the maintainer manually adds them to a maintainer-hosted assetlinks.json. |

Promote items to the friction log once they hit a real user-visible bug.

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
