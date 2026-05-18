# Midnight Kicks — Game Design Spec

**Source of truth for game logic, state machine, UI flows, and integration specs.**
Contract implementation: [`../contract/src/penalty.compact`](../contract/src/penalty.compact)

---

## 1. Match state machine

```
                    ┌──────────────────────────────┐
                    │          WAITING              │
                    │  P1 created match, escrow in  │
                    │  Waiting for P2 to join       │
                    └──────────┬───────────────┬────┘
                               │               │
                          P2 joins         timeout
                               │               │
                    ┌──────────▼──────┐   ┌────▼─────┐
                    │   COMMITTING    │   │ COMPLETE  │
                    │  Both pick 5    │   │ P1 refund │
                    │  directions     │   └──────────┘
                    └──────┬─────┬────┘
                           │     │
                     both commit  timeout (one committed)
                           │     │
                    ┌──────▼──┐  ┌▼──────────┐
                    │REVEALING│  │  COMPLETE  │
                    │Both show│  │  Forfeit → │
                    │preimage │  │  committed │
                    └────┬────┘  │  player    │
                         │       └────────────┘
                    both reveal
                         │
                    ┌────▼─────────────────────┐
                    │  Resolve regulation       │
                    │  Compare 5 rounds         │
                    │  Score: e.g. 3-2          │
                    └─────┬──────────────┬──────┘
                          │              │
                     clear winner     tied
                          │              │
                   ┌──────▼──────┐  ┌───▼──────────┐
                   │  COMPLETE   │  │SD_COMMITTING  │
                   │  Payout to  │  │Both pick 5    │
                   │  winner     │  │new directions │
                   └─────────────┘  └───┬──────────┘
                                        │
                                   (same commit → reveal
                                    → resolve loop)
                                        │
                                   stop at first
                                   decisive round
                                        │
                                   ┌────▼────────┐
                                   │  COMPLETE    │
                                   │  SD winner   │
                                   │  or repeat   │
                                   └──────────────┘
```

### Phases (from contract)

| Phase | Description | Transitions to |
|-------|-------------|----------------|
| `WAITING` | P1 created match + escrowed stake. Waiting for P2. | → `COMMITTING` (P2 joins) or `COMPLETE` (timeout) |
| `COMMITTING` | Both players submit batch commitments (hash of 5 choices + nonce). | → `REVEALING` (both committed) or `COMPLETE` (timeout forfeit) |
| `REVEALING` | Both players reveal preimages. Circuit verifies against commitments. | → `COMPLETE` (clear winner) or `SD_COMMITTING` (tied) |
| `SD_COMMITTING` | Sudden death: both commit a new batch of 5. | → `SD_REVEALING` (both committed) or `COMPLETE` (timeout forfeit) |
| `SD_REVEALING` | Both reveal SD choices. Circuit resolves round-by-round, stops at decisive. | → `COMPLETE` (winner found) or `SD_COMMITTING` (still tied) |
| `COMPLETE` | Match over. Winner claims payout, or draw refund. | Terminal |

### Timeout rules

- Each phase has a `deadline` (unix seconds). Default: 5 minutes.
- If deadline passes with only one player having acted → that player can call `claimTimeout()` to win by forfeit.
- WAITING phase: P1 can cancel and reclaim stake if P2 never joins.

---

## 2. Batch commit-reveal protocol

### Direction encoding

| Value | Direction |
|-------|-----------|
| 0 | LEFT |
| 1 | CENTER |
| 2 | RIGHT |

Any value > 2 is rejected by `validDirection()` check in circuit.

### Coordinate convention

**Directions are always from the SHOOTER's perspective.** Reference:
`unity/Assets/Keeper.cs:105` — the `direction` arg into `Keeper.Dive`
expects `0=left, 2=right` "from the shooter's perspective." The keeper
faces the shooter (mirrored), so `Dive(0)` plays `DiveRight` animation
even though the direction value is "left" — same physical spot, just
opposite-handed body lean.

This matters for the choice UI: when a keeper taps LEFT during the
commit phase, the underlying value committed is `0` (shooter's left),
not "the keeper's left." Without explicit framing, this is confusing —
see §4 *Per-role choice UI* for the open design question and ticket.

### Shoot-vs-keep role per round

The contract alternates shooter/keeper roles by index: `i % 2 == 0` → P1
shoots, otherwise P2 shoots. In a 5-round batch P1 shoots 3 times
(rounds 1, 3, 5), P2 shoots 2 times (rounds 2, 4). Sudden death uses
the same alternation per batch.

**Each player commits ONE direction per round** — the same committed
value is interpreted as the shoot direction or the dive direction
depending on whose turn it is to shoot. Players don't commit separate
"offense" and "defense" arrays.

### Commitment

```
commitment = persistentCommit(BatchPreimage { c0, c1, c2, c3, c4 }, nonce)
```

- `BatchPreimage`: struct of 5 `Uint<8>` direction choices
- `nonce`: 32-byte random (prevents rainbow table attacks on 3^5 = 243 possibilities)
- `persistentCommit`: Midnight's native Pedersen commitment (binding + hiding)

### Reveal

Player reveals all 5 choices + nonce. Circuit recomputes commitment and verifies it matches the stored hash. If mismatch → transaction fails (cheating detected).

### Scoring (regulation)

Each round: if shooter direction ≠ keeper direction → GOAL, else → SAVE.
Roles alternate: odd rounds P1 shoots, even rounds P2 shoots.

```
Round 1: P1 shoots, P2 keeps → compare p1c0 vs p2c0
Round 2: P2 shoots, P1 keeps → compare p2c1 vs p1c1
Round 3: P1 shoots, P2 keeps → compare p1c2 vs p2c2
Round 4: P2 shoots, P1 keeps → compare p2c3 vs p1c3
Round 5: P1 shoots, P2 keeps → compare p1c4 vs p2c4
```

If `shooter_dir != keeper_dir` → scorer gets +1.

### Scoring (sudden death)

Same batch format, same commit-reveal. But resolution stops at the first decisive round:

```
for each round i (0..4):
  P1 shoots: p1_scores = (p1c[i] != p2c[i])
  P2 shoots: p2_scores = (p2c[i] != p1c[i])
  if only one scored → that player wins (decisive)
  if both scored or both missed → continue
```

**ZK property:** rounds after the decisive one are never disclosed. The circuit only reveals results up to and including the decisive round.

If all 5 SD rounds are non-decisive → back to `SD_COMMITTING` for another batch.

---

## 3. Contract circuits

| Circuit | When | Inputs (public) | Witnesses (private) | Effects |
|---------|------|-----------------|--------------------|---------| 
| `constructor` | Deploy | — | — | Set phase=WAITING, P1=caller, deadline=now+1hr |
| `joinMatch` | P2 joins | — | secretKey | Set P2, escrow stake, phase→COMMITTING, deadline=now+5min |
| `commitBatch` | Each player | — | 5 choices + nonce | Store commitment hash, set committed flag |
| `revealBatch` | Each player | — | 5 choices + nonce | Verify vs commitment, store revealed choices |
| `resolveRegulation` | After both reveal | — | — | Score 5 rounds, set winner or phase→SD_COMMITTING |
| `sdCommitBatch` | SD commit | — | 5 choices + nonce | Same as commitBatch for SD |
| `sdRevealAndResolve` | SD reveal | — | 5 choices + nonce | Verify, score round-by-round, stop at decisive |
| `claimTimeout` | Deadline passed | — | — | Forfeit opponent, payout to caller |
| `claimPayout` | COMPLETE phase | — | secretKey | Transfer stake to winner |

---

## 4. Screen flow (IA)

State-based navigation via `KicksScreen` (sealed class in
`app/.../KicksScreen.kt`). No `Navigation-Compose` dep; `KicksActivity`
holds a `mutableStateOf<KicksScreen>` and renders the matching
Composable.

```
[Menu]
  │  ┌────────────────────────────────────────────────────────┐
  │  │ Buttons (top-to-bottom):                                │
  │  │   RESUME MATCH   ← only if KicksSessionStore has a row  │
  │  │   CREATE MATCH                                          │
  │  │   JOIN MATCH                                            │
  │  │   PRACTICE VS AI ← dev affordance, PvAI legacy path     │
  │  └────────────────────────────────────────────────────────┘
  │
  ├─ CREATE MATCH ────► [Creating(address=null)]
  │                       ↓ MatchManager.deployMatch()
  │                       ↓ KicksSessionStore.save({addr, P1, deadline})
  │                     [Creating(address=<hex64>)]
  │                       • QR encodes "midnight://kicks?match=<addr>"
  │                       • COPY button → clipboard
  │                       • CHECK STATUS → MatchManager.awaitOpponentJoin(4s)
  │                         · success → [MatchReady(addr, P1)]
  │                         · timeout → "Still waiting — tap again"
  │                         · the state machine stays Deployed; timeout
  │                           is non-terminal so the user can keep
  │                           polling
  │
  ├─ JOIN MATCH ─────► [Joining(prefilledAddress=null)]
  │                       • Text field accepts 64-char hex contract addr
  │                       • JOIN MATCH enabled when regex matches
  │                       ↓ MatchManager.joinAsP2(address)
  │                       ↓ KicksSessionStore.save({addr, P2, deadline})
  │                     [MatchReady(addr, P2)]
  │
  ├─ midnight://kicks?match=<addr> (deep link from QR or share)
  │                  ─► [Joining(prefilledAddress=addr, "↑ filled from deep link")]
  │                       (same as JOIN MATCH from here)
  │
  ├─ RESUME MATCH ──► reopen by role:
  │                     P1 → [Creating(address=session.address)]
  │                     P2 → [Joining(prefilledAddress=session.address)]
  │
  └─ PRACTICE VS AI ─► launches Unity choice phase with currentRole=null
                        → MatchManager.playAgainstAi(choices) end-to-end
                        → replay (single device)

[MatchReady(address, role)]
  ✓ BOTH PLAYERS IN — You are P1 / P2
  • CONTINUE → launchUnityChoicePhase() with currentRole=role
  • BACK → [Menu]

[Unity Choice Phase] (UnityPlayerGameActivity, separate Activity)
  • Per-round role banner: "YOU SHOOT" or "YOU KEEP" (see §2 coordinate
    convention — both pick L/C/R in shooter-perspective space)
  • L/C/R buttons commit a direction per round
  • 5 picks → choicesLocked back to Kotlin

KicksActivity.handleChoicesLocked dispatches by currentRole:
  null     → MatchManager.playAgainstAi(choices)  ── PvAI single-device
  Player.P1 → MatchManager.playAsP1(choices)       ── submit, await P2 commit,
                                                       reveal, await P2 reveal
  Player.P2 → MatchManager.playAsP2(choices)       ── await P1 commit, submit,
                                                       await P1 reveal, reveal

On result:
  • sessionStore.clear() if PvP (RESUME MATCH disappears from menu)
  • UnityBridge.sendReplay(rounds, p1Score, p2Score, winner) → Unity
    cinematic
  • Unity sends replayComplete → KicksActivity shows score line

[Forfeit / Timeout]
  • Each MatchManager.waitFor* helper has a timeout default
    (DEFAULT_OPPONENT_WAIT_MS). On timeout the state machine goes to
    Failed(prev, e) — user retries or backs out. claimTimeout circuit
    + payout flow not yet wired in the UI (covered by the contract
    but not surfaced in the matchmaking screens).
```

### What's NOT in the IA yet

- **Pause / mid-match exit** — Unity pause button hard-kills the process
  (workaround for shared-process ANR); user relaunches from launcher.
  Proper polish item, see PLAN Phase 5.
- **Sudden death loop** — contract supports SD; orchestrator hand-off
  not yet wired into the UI flow. After regulation resolves tied, the
  state ends at Resolved without auto-routing into SD_COMMITTING.
- **Result screen + leaderboard** — PLAN Phase 4 step "Results screen +
  leaderboard query" still pending.
- **QR scanner** — JoinMatchScreen accepts hex paste / deep link only.
  Google Code Scanner is PLAN Phase 5 polish.

### State polling

While waiting for opponent actions, the Kotlin layer subscribes to the
contract state via the indexer (`MatchManager.awaitContractState` →
`StatePoller`, 3s tick). When the opponent's `committed`/`revealed`
flags change → the suspending `waitFor*` helper returns and the state
machine advances. The poller is started lazily inside the wait window
and torn down on return — no background polling between waits.

### Per-role choice UI — OPEN DESIGN

The coordinate convention (§2) means a keeper tapping "LEFT" commits
`0` (shooter's left), not "the keeper's left." Today's GUI overlay
shows a `YOU SHOOT` / `YOU KEEP` banner above L/C/R buttons but
doesn't visualize the goal or the perspective. Three candidates,
none picked yet:

1. **Reframe the keep prompt** — banner copy says "predict where they'll
   kick (left/center/right of goal)" so the player understands they're
   picking the *target corner*, not their dive direction. Cheap.
2. **Visual goal diagram** — render a 2D goal image (3 zones, shooter
   view) above the buttons. Buttons tap zones of the diagram. Same
   for both roles. One asset + layout work.
3. **Per-role 3D camera + interaction** — shoot rounds show goal from
   behind the ball, keep rounds show shooter from inside the goal.
   Player taps regions of the 3D scene. Substantial Unity work, post-launch.

Locked-in by the design conversation: contract stays in shooter-coord
space; we change presentation, not semantics.

---

## 5. Unity ↔ Kotlin bridge

UaaL renders Unity full-screen as an Android Activity. Communication via `UnitySendMessage` (Kotlin → Unity) and `AndroidJavaObject` callbacks (Unity → Kotlin).

### Kotlin → Unity messages

```json
// Start choice phase
// `roles` is the per-round role from THIS device's perspective. Five
// entries, each "shoot" or "keep". P1 = [shoot, keep, shoot, keep, shoot];
// P2 = [keep, shoot, keep, shoot, keep]; PvAI = P1 pattern (human is
// always P1 in PvAI). Unity uses roles[i] to label each pick.
{
  "type": "choicePhase",
  "round": "regulation",
  "roles": ["shoot", "keep", "shoot", "keep", "shoot"]
}

// Start replay with results
{
  "type": "replay",
  "rounds": [
    { "round": 1, "shooter": "P1", "shootDir": 0, "keepDir": 2, "result": "goal" },
    { "round": 2, "shooter": "P2", "shootDir": 1, "keepDir": 1, "result": "save" },
    { "round": 3, "shooter": "P1", "shootDir": 2, "keepDir": 0, "result": "goal" },
    { "round": 4, "shooter": "P2", "shootDir": 0, "keepDir": 2, "result": "goal" },
    { "round": 5, "shooter": "P1", "shootDir": 1, "keepDir": 0, "result": "goal" }
  ],
  "finalScore": { "p1": 3, "p2": 2 },
  "winner": "P1"
}

// Sudden death replay (only decisive rounds shown)
{
  "type": "suddenDeathReplay",
  "rounds": [
    { "round": 1, "shooter": "P1", "shootDir": 2, "keepDir": 2, "result": "save" },
    { "round": 1, "shooter": "P2", "shootDir": 0, "keepDir": 0, "result": "save" },
    { "round": 2, "shooter": "P1", "shootDir": 1, "keepDir": 0, "result": "goal" },
    { "round": 2, "shooter": "P2", "shootDir": 2, "keepDir": 2, "result": "save" }
  ],
  "winner": "P1"
}
```

### Unity → Kotlin messages

```json
// Player made choices
{
  "type": "choicesLocked",
  "choices": [0, 1, 2, 0, 1]
}

// Replay finished
{ "type": "replayComplete" }

// Pause requested (user tapped the pause HUD button)
// KicksActivity logs it and updates the menu's status line. Unity
// immediately follows up with Process.killProcess to bypass Unity's
// 10s onDestroy hang on the shared OS process (otherwise KicksActivity
// ANRs while Unity tears down). User relaunches from the launcher.
{ "type": "matchPaused" }
```

---

## 6. Edge cases

| Scenario | Handling |
|----------|----------|
| Both players pick identical directions all 5 rounds | Score 0-0 → sudden death |
| Both miss all 5 in sudden death | Non-decisive → another SD batch |
| Player commits then closes app | Timeout → opponent claims forfeit |
| Both players timeout (neither commits) | Both can reclaim their stake |
| Player tries to reveal wrong preimage | Circuit rejects (commitment mismatch) |
| Player submits invalid direction (>2) | `validDirection()` check fails in circuit |
| Network disconnect during replay | On-chain result unchanged, replay is cosmetic |
| Player opens app after match resolved | Sees result screen, can claim payout |
| 10+ sudden death batches (extreme tie) | Protocol supports infinite batches. Practically impossible (3^5 = 243 combinations). |
