# Midnight Kicks — Penalty Shootout on Midnight

**Target:** FIFA World Cup 2026 (June 11 - July 19)
**Updated:** 2026-04-28

---

## Concept

PvP penalty shootout on Android. Two players, five rounds, real stakes (PREPROD NIGHT). Unity 3D + Midnight ZK proofs. The contract is the referee — no server, no trust.

**Why:** World Cup timing. Trojan horse for ZK (players don't know they're using zero-knowledge proofs). SDK validation before Kuira ships. Open-source connector SDK as the "Build on Midnight" story.

## Game flow

1. **CHOICE** — both players pick 5 directions simultaneously, submit ONE transaction each (batch commit)
2. **PROVE** — one ZK circuit compares all 5 rounds at once, single proof
3. **REPLAY** — Unity plays the full match cinematically (stadium intro masks proof latency)

Sudden death: batches of 5, circuit stops at decisive round. Unrevealed rounds stay private (ZK property).

**Anti-cheat:** commit-reveal. Hash of 5 choices + nonces stored as private state. ZK circuit proves revealed choices match commitments. Cannot change choices after commit.

Detailed game logic, state machine, circuit specs, UI flows, and Unity bridge spec in [`GAME_DESIGN.md`](GAME_DESIGN.md). Current Unity work and asset checklist in [`../ROADMAP.md`](../ROADMAP.md).

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
  - Private state: 5 choices + nonces per player
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
- [ ] **Phase 3 — Unity + Kotlin integration**
  - [x] kick-prototype with ball physics + 3 directions (URP, IL2CPP, arm64)
  - [x] UaaL export script (ExportAndroidLibrary.cs, batch mode CLI)
  - [x] Separate Gradle project (AGP 9.0.0, independent from Kuira build)
  - [x] GameController.cs — JSON bridge, 5-round choice UI, replay stub
  - [x] UnityBridge.kt — Kotlin↔Unity JSON messaging
  - [x] KicksActivity — main menu + deep link handler
  - [ ] GameController receiving choicePhase + sending choicesLocked (end-to-end)
  - [ ] Replay system (5 rounds from JSON) + stadium intro cinematic
  - [x] MatchManager — deploy/join/commit/reveal/claim circuit calls (state-machine refactor 2026-05-12: discrete suspend transitions, `StateFlow<MatchState>` as source of truth, `KicksActivity` is now a thin presenter over the SDK)
  - [x] StatePoller — watch opponent actions via indexer (2026-05-13: 3s poll on `MidnightConfig.queryState`, parses `penalty.compact` ledger via verified cell indices, exposed on `MatchManager.contractState: StateFlow`. Observer-only this commit; next commit wires `waitForP2Committed/Revealed` into the orchestrator)
- [ ] **Phase 4 — Full two-player game**
  - [ ] Onboarding (passkey → biometric → play)
  - [ ] Matchmaking (QR code + deep link)
  - [ ] Two-emulator E2E on localnet
  - [ ] Results screen + leaderboard query
- [ ] **Phase 5 — Polish + release**
  - [ ] APK size audit (< 100MB), proof latency tuning
  - [ ] Error handling, timeout UX, disconnect recovery
  - [ ] Play Store listing, closed beta
- [ ] **Phase 6 — Launch**
  - [ ] Open beta → announce (World Cup timing, June 11)

---

## SDK friction log

Every friction point building BBoard standalone → becomes SDK improvement.

| # | Friction | Severity | Fix |
|---|---------|----------|-----|
| 1 | Fee fallback to INITIAL_PARAMETERS → 66T specks → imbalanced tx → error 170 | Critical | Zero-fee detection. Need convergence loop for mainnet. |
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
| 9 | Typed ledger wrapper from `.compact` | Every dApp parses `queryState`'s positional `JSONArray` by hand against cell indices it reads out of the compiled contract JS. Codegen a typed `Ledger` class per contract from the `.compact` source (same shape as the JS `ledger()` getter). | `ContractStateSnapshot.parse` in midnight-kicks hand-encodes 25 cell indices verified against `penalty-contract.js:3603+`. |
| 10 | Public block subscription | `MidnightSdk.indexerClient` is `private`, so app code can't reach `subscribeToBlocks()` for push-based contract-state watching. Expose either the indexer or a thin `MidnightContract.stateFlow()` over it. | `StatePoller` falls back to 3s polling. |

Promote items to the friction log once they hit a real user-visible bug.

---

## Decision log

| Decision | Choice | Why |
|----------|--------|-----|
| Batch vs per-round | Batch (5 choices, 1 tx) | 2 txs per match vs 20. Cinematic replay. |
| Sudden death | Batches of 5, stop at decisive | Unrevealed rounds private. No infinite loops. |
| Unity vs Compose | Unity (UaaL) | 3D stadium, ball physics, cameras. |
| Standalone repo | Separate from Kuira | Tests SDK boundaries. Separate release. |
| Pairing | QR + deep links (built in Kicks) | Simpler than connector transports. |
| Key curve | secp256k1 (advocate P-256) | Midnight accepts secp256k1 today. |
| keyAuthorization | Self-verifiable (TEE signs) | No server trust needed. |
| DID | One per user from root passkey | Sigil = one identity. |
| Recovery | PRF-encrypted cloud backup | Zero words. Passkey syncs → biometric → restored. |
| Gas | PREPROD faucet (provider-pay on mainnet) | Lowest friction for testnet. |
| Proving | On-device (local) | Proves hardware capability. No server. |
