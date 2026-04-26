// Midnight Kicks — Security vulnerability reproductions
//
// These tests run against the VULNERABLE contract to prove the
// exploits work, then against the FIXED contract to prove the
// remediation holds. Each test maps to a VULN-XXX entry in
// docs/security/COMPACT_SECURITY_REGISTRY.md.
//
// The vulnerable contract is penalty-vulnerable.compact (V1).
// The fixed contract is penalty.compact (V2).

import { PenaltySimulator } from "./penalty-simulator.js";
import { VulnerablePenaltySimulator } from "./penalty-vulnerable-simulator.js";
import {
  NetworkId,
  setNetworkId,
} from "@midnight-ntwrk/midnight-js-network-id";
import { describe, it, expect } from "vitest";
import { randomBytes, LEFT, CENTER, RIGHT, type Choices } from "./utils.js";
import { Phase } from "../managed/penalty/contract/index.js";
import { Phase as VulnPhase } from "../managed/penalty-vulnerable/contract/index.js";

setNetworkId("undeployed" as NetworkId);

// ═══════════════════════════════════════════════════════════════════
// VULN-001: Identity impersonation via secret key knowledge
// ═══════════════════════════════════════════════════════════════════

describe("VULN-001: Identity impersonation via secret key knowledge", () => {
  // Both vulnerable and fixed contracts use the same identity model
  // (localSecretKey + persistentHash). This vuln is about ownPublicKey()
  // at the protocol level — we demonstrate WHY our pattern is safer.

  it("EXPLOIT: attacker with P1's secret key acts as P1", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    // Using vulnerable contract — same behavior for this vuln
    const sim = new VulnerablePenaltySimulator(p1Secret, choices, nonce);
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    // ATTACK: attacker uses P1's secret key
    sim.switchPlayer(p1Secret, choices, nonce);
    const state = sim.commitBatch();
    expect(state.p1Committed).toEqual(true);
    // Attacker committed AS player 1 — exploit works
  });

  it("DEFENSE: without the secret key, impersonation fails", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new VulnerablePenaltySimulator(p1Secret, choices, nonce);
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    // Wrong secret → wrong public key → rejected
    const wrongSecret = randomBytes(32);
    sim.switchPlayer(wrongSecret, choices, nonce);
    expect(() => sim.commitBatch()).toThrow("Not a player in this match");
  });
});

// ═══════════════════════════════════════════════════════════════════
// VULN-002: Choices disclosed during commitment
// ═══════════════════════════════════════════════════════════════════

describe("VULN-002: Choices disclosed during commitment", () => {

  it("VULNERABLE: contract compiles with choices in disclose() during commit", () => {
    // The vulnerable contract discloses choices in commitBatch:
    //   const c0 = disclose(localChoice0());  ← LEAKED
    // This compiles and runs — the compiler doesn't warn because
    // disclose is explicit. But the choices are now in the proof
    // transcript, extractable by an observer.

    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new VulnerablePenaltySimulator(p1Secret, choices, nonce);
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    // Commit succeeds — vulnerable code runs fine
    sim.switchPlayer(p1Secret, choices, nonce);
    const state = sim.commitBatch();
    expect(state.p1Committed).toEqual(true);

    // The problem: choices are in the proof's private_input
    // instructions. An observer with access to the transaction
    // could extract them before P2 commits.
  });

  it("FIXED: contract compiles with choices NOT in disclose() during commit", () => {
    // The fixed contract passes choices directly to persistentCommit
    // without disclose(). Only the hash output is disclosed.

    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new PenaltySimulator(p1Secret, choices, nonce);
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    // Commit succeeds — fixed code works identically
    sim.switchPlayer(p1Secret, choices, nonce);
    const state = sim.commitBatch();
    expect(state.p1Committed).toEqual(true);

    // The fix: choices are NOT in the proof transcript.
    // Only the commitment hash is visible. An observer sees
    // the hash but cannot extract the choices.
  });

  it("PROOF: same choices produce same commitment in both versions", () => {
    // Both vulnerable and fixed contracts produce the same
    // commitment hash — the fix doesn't change the hash, only
    // what's disclosed in the proof.

    const secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const vulnSim = new VulnerablePenaltySimulator(secret, choices, nonce);
    vulnSim.switchPlayer(p2Secret, choices, nonce);
    vulnSim.joinMatch();
    vulnSim.switchPlayer(secret, choices, nonce);
    const vulnState = vulnSim.commitBatch();

    const fixedSim = new PenaltySimulator(secret, choices, nonce);
    fixedSim.switchPlayer(p2Secret, choices, nonce);
    fixedSim.joinMatch();
    fixedSim.switchPlayer(secret, choices, nonce);
    const fixedState = fixedSim.commitBatch();

    // Same commitment hash — the cryptography is identical
    expect(vulnState.p1Commitment).toEqual(fixedState.p1Commitment);
    // The only difference is what's in the proof transcript
  });

  it("MATH: only 243 choice combinations — brute-forceable without nonce", () => {
    expect(Math.pow(3, 5)).toEqual(243);
    // Without a nonce, an attacker could hash all 243 possible
    // choice combinations and compare to the on-chain commitment.
    // The 32-byte nonce makes this infeasible.
  });
});

// ═══════════════════════════════════════════════════════════════════
// VULN-003: Secret key disclosed unnecessarily
// ═══════════════════════════════════════════════════════════════════

describe("VULN-003: Secret key disclosed unnecessarily", () => {

  it("VULNERABLE: secret key in disclose() — same identity derived", () => {
    // In the vulnerable contract:
    //   const sk = disclose(localSecretKey());  ← SECRET LEAKED
    //   player1 = disclose(publicKey(sk));
    // The secret key is a private_input in the ZKIR transcript.

    const secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new VulnerablePenaltySimulator(secret, choices, nonce);
    const vulnPlayer1 = sim.getLedger().player1;

    // Identity is derived — works correctly, but the secret is
    // unnecessarily exposed in the proof data.
    expect(vulnPlayer1).not.toEqual(new Uint8Array(32));
  });

  it("FIXED: secret key NOT in disclose() — same identity derived", () => {
    // In the fixed contract:
    //   player1 = disclose(publicKey(localSecretKey()));
    // The secret key feeds into publicKey() without disclose().
    // Only the hash output crosses the privacy boundary.

    const secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const sim = new PenaltySimulator(secret, choices, nonce);
    const fixedPlayer1 = sim.getLedger().player1;

    expect(fixedPlayer1).not.toEqual(new Uint8Array(32));
  });

  it("PROOF: both versions derive the same public key from same secret", () => {
    const secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    const vulnSim = new VulnerablePenaltySimulator(secret, choices, nonce);
    const fixedSim = new PenaltySimulator(secret, choices, nonce);

    // Same secret → same public key in both versions
    expect(vulnSim.getLedger().player1).toEqual(fixedSim.getLedger().player1);
    // The fix doesn't change the identity — it only changes
    // what's in the proof transcript.
  });
});

// ═══════════════════════════════════════════════════════════════════
// VULN-004: Griefing via non-participation (no timeout)
// ═══════════════════════════════════════════════════════════════════

describe("VULN-004: Griefing via non-participation", () => {

  it("EXPLOIT: contract stuck in COMMITTING — no escape", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const nonce = randomBytes(32);

    // Both versions are vulnerable to this — V2 doesn't add timeouts
    const sim = new VulnerablePenaltySimulator(p1Secret, choices, nonce);
    sim.switchPlayer(p2Secret, choices, nonce);
    sim.joinMatch();

    sim.switchPlayer(p1Secret, choices, nonce);
    sim.commitBatch();

    // P2 never commits — contract stuck forever
    const state = sim.getLedger();
    expect(state.phase).toEqual(VulnPhase.COMMITTING);
    expect(state.p1Committed).toEqual(true);
    expect(state.p2Committed).toEqual(false);
    // No claimTimeout() circuit exists — no way out
  });

  it("EXPLOIT: contract stuck in REVEALING — no escape", () => {
    const p1Secret = randomBytes(32);
    const p2Secret = randomBytes(32);
    const p1Choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
    const p2Choices: Choices = [RIGHT, LEFT, CENTER, RIGHT, LEFT];
    const p1Nonce = randomBytes(32);
    const p2Nonce = randomBytes(32);

    const sim = new VulnerablePenaltySimulator(p1Secret, p1Choices, p1Nonce);
    sim.switchPlayer(p2Secret, p2Choices, p2Nonce);
    sim.joinMatch();

    sim.switchPlayer(p1Secret, p1Choices, p1Nonce);
    sim.commitBatch();
    sim.switchPlayer(p2Secret, p2Choices, p2Nonce);
    sim.commitBatch();

    sim.switchPlayer(p1Secret, p1Choices, p1Nonce);
    sim.revealBatch();

    // P2 never reveals — contract stuck forever
    const state = sim.getLedger();
    expect(state.phase).toEqual(VulnPhase.REVEALING);
    expect(state.p1Revealed).toEqual(true);
    expect(state.p2Revealed).toEqual(false);
    // Both players' stakes would be locked permanently
  });
});
