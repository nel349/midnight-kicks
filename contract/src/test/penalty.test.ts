// Midnight Kicks — Penalty contract unit tests
// Tests the full match lifecycle using local circuit execution

import { PenaltySimulator } from "./penalty-simulator.js";
import {
  NetworkId,
  setNetworkId,
} from "@midnight-ntwrk/midnight-js-network-id";
import { describe, it, expect, beforeEach } from "vitest";
import { randomBytes, LEFT, CENTER, RIGHT, type Choices } from "./utils.js";
import { Phase } from "../managed/penalty/contract/index.js";

setNetworkId("undeployed" as NetworkId);

// Test fixtures
const p1Key = randomBytes(32);
const p2Key = randomBytes(32);
const p1Nonce = randomBytes(32);
const p2Nonce = randomBytes(32);

// P1 shoots rounds 0,2,4 — P2 shoots rounds 1,3
// If shooter != keeper → GOAL
const p1Choices: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
const p2Choices: Choices = [RIGHT, LEFT, RIGHT, CENTER, LEFT];
// Expected results:
//   R0: P1 shoots LEFT,  P2 keeps RIGHT  → different → P1 GOAL (1-0)
//   R1: P2 shoots LEFT,  P1 keeps CENTER → different → P2 GOAL (1-1)
//   R2: P1 shoots RIGHT, P2 keeps RIGHT  → same     → SAVE    (1-1)
//   R3: P2 shoots CENTER,P1 keeps LEFT   → different → P2 GOAL (1-2)
//   R4: P1 shoots RIGHT, P2 keeps LEFT   → different → P1 GOAL (2-2)
// Result: 2-2 draw

describe("Penalty contract", () => {
  describe("constructor", () => {
    it("initializes in WAITING phase with player 1 set", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      const state = sim.getLedger();
      expect(state.phase).toEqual(Phase.WAITING);
      expect(state.p1Committed).toEqual(false);
      expect(state.p2Committed).toEqual(false);
      expect(state.p1Revealed).toEqual(false);
      expect(state.p2Revealed).toEqual(false);
      expect(state.isDraw).toEqual(false);
    });
  });

  describe("joinMatch", () => {
    it("advances to COMMITTING phase", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      const state = sim.joinMatch();
      expect(state.phase).toEqual(Phase.COMMITTING);
    });

    it("rejects joining your own match", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      // P1 tries to join their own match
      expect(() => sim.joinMatch()).toThrow("Cannot join your own match");
    });

    it("rejects joining when not in WAITING phase", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.joinMatch(); // now in COMMITTING
      const p3Key = randomBytes(32);
      sim.switchPlayer(p3Key, p1Choices, p1Nonce);
      expect(() => sim.joinMatch()).toThrow("Match not in WAITING phase");
    });
  });

  describe("commitBatch", () => {
    it("records player 1 commitment", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.joinMatch();

      // P1 commits
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      const state = sim.commitBatch();
      expect(state.p1Committed).toEqual(true);
      expect(state.p2Committed).toEqual(false);
      expect(state.phase).toEqual(Phase.COMMITTING); // still waiting for P2
    });

    it("advances to REVEALING when both commit", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.joinMatch();

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();

      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      const state = sim.commitBatch();
      expect(state.p1Committed).toEqual(true);
      expect(state.p2Committed).toEqual(true);
      expect(state.phase).toEqual(Phase.REVEALING);
    });

    it("rejects double commitment by same player", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.joinMatch();

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      expect(() => sim.commitBatch()).toThrow("Player 1 already committed");
    });

    it("rejects non-player commitment", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.joinMatch();

      const outsider = randomBytes(32);
      sim.switchPlayer(outsider, p1Choices, p1Nonce);
      expect(() => sim.commitBatch()).toThrow("Not a player in this match");
    });
  });

  describe("revealBatch", () => {
    it("rejects reveal before both committed", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.joinMatch();

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      // Try to reveal while still in COMMITTING
      expect(() => sim.revealBatch()).toThrow("Match not in REVEALING phase");
    });

    it("rejects reveal with wrong choices (commitment mismatch)", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.joinMatch();

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.commitBatch();

      // P1 reveals with DIFFERENT choices than committed
      const wrongChoices: Choices = [RIGHT, RIGHT, RIGHT, RIGHT, RIGHT];
      sim.switchPlayer(p1Key, wrongChoices, p1Nonce);
      expect(() => sim.revealBatch()).toThrow("Commitment mismatch");
    });

    it("rejects reveal with wrong nonce", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.joinMatch();

      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.commitBatch();

      // P1 reveals with wrong nonce
      const wrongNonce = randomBytes(32);
      sim.switchPlayer(p1Key, p1Choices, wrongNonce);
      expect(() => sim.revealBatch()).toThrow("Commitment mismatch");
    });
  });

  describe("full match — draw scenario", () => {
    it("resolves to a draw (2-2)", () => {
      const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.joinMatch();

      // Both commit
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.commitBatch();

      // Both reveal
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.revealBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      const state = sim.revealBatch();

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Score).toEqual(2n);
      expect(state.p2Score).toEqual(2n);
      expect(state.isDraw).toEqual(true);
    });
  });

  describe("full match — P1 wins", () => {
    it("resolves with P1 as winner (3-1)", () => {
      // P1: shoots LEFT, keeps LEFT, shoots LEFT, keeps LEFT, shoots LEFT
      // P2: keeps CENTER, shoots RIGHT, keeps CENTER, shoots RIGHT, keeps CENTER
      // R0: P1 LEFT vs P2 CENTER → different → P1 GOAL
      // R1: P2 RIGHT vs P1 LEFT → different → P2 GOAL
      // R2: P1 LEFT vs P2 CENTER → different → P1 GOAL
      // R3: P2 RIGHT vs P1 LEFT → different → P2 GOAL ... wait
      // Need a scenario where P1 wins clearly

      // P1: [LEFT, LEFT, LEFT, LEFT, LEFT] — always goes left
      // P2: [RIGHT, RIGHT, LEFT, RIGHT, RIGHT] — keeps right mostly, shoots right
      // R0: P1 shoots LEFT, P2 keeps RIGHT → GOAL P1 (1-0)
      // R1: P2 shoots RIGHT, P1 keeps LEFT → GOAL P2 (1-1)
      // R2: P1 shoots LEFT, P2 keeps LEFT → SAVE (1-1)
      // R3: P2 shoots RIGHT, P1 keeps LEFT → GOAL P2 (1-2)
      // R4: P1 shoots LEFT, P2 keeps RIGHT → GOAL P1 (2-2) — draw again

      // Let's construct a clear P1 win:
      // P1: [LEFT, CENTER, RIGHT, CENTER, LEFT]
      // P2: [RIGHT, CENTER, LEFT, LEFT, RIGHT]
      // R0: P1 shoots LEFT, P2 keeps RIGHT → GOAL P1 (1-0)
      // R1: P2 shoots CENTER, P1 keeps CENTER → SAVE (1-0)
      // R2: P1 shoots RIGHT, P2 keeps LEFT → GOAL P1 (2-0)
      // R3: P2 shoots LEFT, P1 keeps CENTER → GOAL P2 (2-1)
      // R4: P1 shoots LEFT, P2 keeps RIGHT → GOAL P1 (3-1)

      const p1Win: Choices = [LEFT, CENTER, RIGHT, CENTER, LEFT];
      const p2Lose: Choices = [RIGHT, CENTER, LEFT, LEFT, RIGHT];
      const n1 = randomBytes(32);
      const n2 = randomBytes(32);

      const sim = new PenaltySimulator(p1Key, p1Win, n1);
      sim.switchPlayer(p2Key, p2Lose, n2);
      sim.joinMatch();

      sim.switchPlayer(p1Key, p1Win, n1);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Lose, n2);
      sim.commitBatch();

      sim.switchPlayer(p1Key, p1Win, n1);
      sim.revealBatch();
      sim.switchPlayer(p2Key, p2Lose, n2);
      const state = sim.revealBatch();

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Score).toEqual(3n);
      expect(state.p2Score).toEqual(1n);
      expect(state.isDraw).toEqual(false);
      // winner should be player1's public key
      expect(state.winner).toEqual(sim.getLedger().player1);
    });
  });

  describe("full match — P2 wins", () => {
    it("resolves with P2 as winner", () => {
      // P1: [LEFT, LEFT, LEFT, LEFT, LEFT] — always left
      // P2: [LEFT, RIGHT, LEFT, RIGHT, LEFT] — matches P1 on shoots, misses on keeps
      // R0: P1 shoots LEFT, P2 keeps LEFT → SAVE (0-0)
      // R1: P2 shoots RIGHT, P1 keeps LEFT → GOAL P2 (0-1)
      // R2: P1 shoots LEFT, P2 keeps LEFT → SAVE (0-1)
      // R3: P2 shoots RIGHT, P1 keeps LEFT → GOAL P2 (0-2)
      // R4: P1 shoots LEFT, P2 keeps LEFT → SAVE (0-2)

      const p1Lose: Choices = [LEFT, LEFT, LEFT, LEFT, LEFT];
      const p2Win: Choices = [LEFT, RIGHT, LEFT, RIGHT, LEFT];
      const n1 = randomBytes(32);
      const n2 = randomBytes(32);

      const sim = new PenaltySimulator(p1Key, p1Lose, n1);
      sim.switchPlayer(p2Key, p2Win, n2);
      sim.joinMatch();

      sim.switchPlayer(p1Key, p1Lose, n1);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Win, n2);
      sim.commitBatch();

      sim.switchPlayer(p1Key, p1Lose, n1);
      sim.revealBatch();
      sim.switchPlayer(p2Key, p2Win, n2);
      const state = sim.revealBatch();

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Score).toEqual(0n);
      expect(state.p2Score).toEqual(2n);
      expect(state.isDraw).toEqual(false);
      expect(state.winner).toEqual(sim.getLedger().player2);
    });
  });
});
