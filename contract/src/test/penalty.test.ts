// Midnight Kicks — Penalty contract unit tests
// Tests the full match lifecycle including sudden death and timeouts

import { PenaltySimulator } from "./penalty-simulator.js";
import {
  NetworkId,
  setNetworkId,
} from "@midnight-ntwrk/midnight-js-network-id";
import { describe, it, expect } from "vitest";
import { randomBytes, LEFT, CENTER, RIGHT, type Choices } from "./utils.js";
import { Phase } from "../managed/penalty/contract/index.js";

setNetworkId("undeployed" as NetworkId);

// Helper: create match + P2 joins, ready to commit
function setupMatch(p1Choices: Choices, p2Choices: Choices) {
  const p1Key = randomBytes(32);
  const p2Key = randomBytes(32);
  const p1Nonce = randomBytes(32);
  const p2Nonce = randomBytes(32);
  const sim = new PenaltySimulator(p1Key, p1Choices, p1Nonce);
  sim.switchPlayer(p2Key, p2Choices, p2Nonce);
  sim.joinMatch();
  return { sim, p1Key, p2Key, p1Nonce, p2Nonce, p1Choices, p2Choices };
}

// Helper: run full commit + reveal cycle
function commitAndReveal(
  sim: PenaltySimulator,
  p1Key: Uint8Array, p1Choices: Choices, p1Nonce: Uint8Array,
  p2Key: Uint8Array, p2Choices: Choices, p2Nonce: Uint8Array,
) {
  sim.switchPlayer(p1Key, p1Choices, p1Nonce);
  sim.commitBatch();
  sim.switchPlayer(p2Key, p2Choices, p2Nonce);
  sim.commitBatch();
  sim.switchPlayer(p1Key, p1Choices, p1Nonce);
  sim.revealBatch();
  sim.switchPlayer(p2Key, p2Choices, p2Nonce);
  return sim.revealBatch();
}

// Draw choices (2-2)
const DRAW_P1: Choices = [LEFT, CENTER, RIGHT, LEFT, RIGHT];
const DRAW_P2: Choices = [RIGHT, LEFT, RIGHT, CENTER, LEFT];

describe("Penalty contract", () => {
  describe("constructor + joinMatch", () => {
    it("initializes in WAITING phase", () => {
      const sim = new PenaltySimulator(randomBytes(32), DRAW_P1, randomBytes(32));
      const state = sim.getLedger();
      expect(state.phase).toEqual(Phase.WAITING);
    });

    it("advances to COMMITTING when P2 joins", () => {
      const sim = new PenaltySimulator(randomBytes(32), DRAW_P1, randomBytes(32));
      sim.switchPlayer(randomBytes(32), DRAW_P2, randomBytes(32));
      const state = sim.joinMatch();
      expect(state.phase).toEqual(Phase.COMMITTING);
    });

    it("rejects joining your own match", () => {
      const key = randomBytes(32);
      const sim = new PenaltySimulator(key, DRAW_P1, randomBytes(32));
      expect(() => sim.joinMatch()).toThrow("Cannot join your own match");
    });

    it("rejects joining when not in WAITING phase", () => {
      const { sim } = setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(randomBytes(32), DRAW_P1, randomBytes(32));
      expect(() => sim.joinMatch()).toThrow("Match not in WAITING phase");
    });
  });

  describe("commitBatch", () => {
    it("records P1 commitment, stays in COMMITTING", () => {
      const { sim, p1Key, p1Choices, p1Nonce } = setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      const state = sim.commitBatch();
      expect(state.p1Committed).toEqual(true);
      expect(state.p2Committed).toEqual(false);
      expect(state.phase).toEqual(Phase.COMMITTING);
    });

    it("advances to REVEALING when both commit", () => {
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      const state = sim.commitBatch();
      expect(state.phase).toEqual(Phase.REVEALING);
    });

    it("rejects double commitment", () => {
      const { sim, p1Key, p1Choices, p1Nonce } = setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      expect(() => sim.commitBatch()).toThrow("Player 1 already committed");
    });

    it("rejects non-player", () => {
      const { sim } = setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(randomBytes(32), DRAW_P1, randomBytes(32));
      expect(() => sim.commitBatch()).toThrow("Not a player in this match");
    });
  });

  describe("revealBatch", () => {
    it("rejects reveal before both committed", () => {
      const { sim, p1Key, p1Choices, p1Nonce } = setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      expect(() => sim.revealBatch()).toThrow("Match not in a reveal phase");
    });

    it("rejects wrong choices (commitment mismatch)", () => {
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.commitBatch();

      sim.switchPlayer(p1Key, [RIGHT, RIGHT, RIGHT, RIGHT, RIGHT], p1Nonce);
      expect(() => sim.revealBatch()).toThrow("Commitment mismatch");
    });

    it("rejects wrong nonce", () => {
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(DRAW_P1, DRAW_P2);
      sim.switchPlayer(p1Key, p1Choices, p1Nonce);
      sim.commitBatch();
      sim.switchPlayer(p2Key, p2Choices, p2Nonce);
      sim.commitBatch();

      sim.switchPlayer(p1Key, p1Choices, randomBytes(32));
      expect(() => sim.revealBatch()).toThrow("Commitment mismatch");
    });
  });

  describe("full match — P1 wins (3-1)", () => {
    it("resolves with P1 as winner", () => {
      const p1Win: Choices = [LEFT, CENTER, RIGHT, CENTER, LEFT];
      const p2Lose: Choices = [RIGHT, CENTER, LEFT, LEFT, RIGHT];
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(p1Win, p2Lose);
      const state = commitAndReveal(sim, p1Key, p1Choices, p1Nonce, p2Key, p2Choices, p2Nonce);

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Score).toEqual(3n);
      expect(state.p2Score).toEqual(1n);
      expect(state.isDraw).toEqual(false);
      expect(state.winner).toEqual(sim.getLedger().player1);
    });
  });

  describe("full match — P2 wins (0-2)", () => {
    it("resolves with P2 as winner", () => {
      const p1Lose: Choices = [LEFT, LEFT, LEFT, LEFT, LEFT];
      const p2Win: Choices = [LEFT, RIGHT, LEFT, RIGHT, LEFT];
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(p1Lose, p2Win);
      const state = commitAndReveal(sim, p1Key, p1Choices, p1Nonce, p2Key, p2Choices, p2Nonce);

      expect(state.phase).toEqual(Phase.COMPLETE);
      expect(state.p1Score).toEqual(0n);
      expect(state.p2Score).toEqual(2n);
      expect(state.winner).toEqual(sim.getLedger().player2);
    });
  });

  describe("full match — draw → sudden death", () => {
    it("draw enters SD_COMMITTING phase", () => {
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(DRAW_P1, DRAW_P2);
      const state = commitAndReveal(sim, p1Key, p1Choices, p1Nonce, p2Key, p2Choices, p2Nonce);

      expect(state.phase).toEqual(Phase.SD_COMMITTING);
      expect(state.sdRound).toEqual(1n);
      // Commit/reveal flags reset for SD
      expect(state.p1Committed).toEqual(false);
      expect(state.p2Committed).toEqual(false);
    });

    it("SD resolves when P1 scores and P2 misses", () => {
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(DRAW_P1, DRAW_P2);
      commitAndReveal(sim, p1Key, p1Choices, p1Nonce, p2Key, p2Choices, p2Nonce);

      // SD round: P1 scores R0, P2 misses R1
      // R0: P1 shoots LEFT, P2 keeps RIGHT → GOAL
      // R1: P2 shoots RIGHT, P1 keeps LEFT → different → wait...
      // That's both scoring. Need P1 scores, P2 doesn't.
      // R0: P1 LEFT vs P2 RIGHT → P1 GOAL
      // R1: P2 LEFT vs P1 LEFT → SAVE (P2 blocked!)
      const sdP1: Choices = [LEFT, LEFT, LEFT, LEFT, LEFT];
      const sdP2: Choices = [RIGHT, LEFT, LEFT, LEFT, LEFT];
      const sdN1 = randomBytes(32);
      const sdN2 = randomBytes(32);

      sim.switchPlayer(p1Key, sdP1, sdN1);
      sim.commitBatch();
      sim.switchPlayer(p2Key, sdP2, sdN2);
      sim.commitBatch();

      sim.switchPlayer(p1Key, sdP1, sdN1);
      sim.revealBatch();
      sim.switchPlayer(p2Key, sdP2, sdN2);
      const sdState = sim.revealBatch();

      expect(sdState.phase).toEqual(Phase.COMPLETE);
      expect(sdState.winner).toEqual(sim.getLedger().player1);
    });

    it("SD resolves when P2 scores and P1 misses", () => {
      const { sim, p1Key, p2Key, p1Choices, p2Choices, p1Nonce, p2Nonce } =
        setupMatch(DRAW_P1, DRAW_P2);
      commitAndReveal(sim, p1Key, p1Choices, p1Nonce, p2Key, p2Choices, p2Nonce);

      // R0: P1 LEFT vs P2 LEFT → SAVE (P1 blocked)
      // R1: P2 RIGHT vs P1 LEFT → P2 GOAL
      const sdP1: Choices = [LEFT, LEFT, LEFT, LEFT, LEFT];
      const sdP2: Choices = [LEFT, RIGHT, LEFT, LEFT, LEFT];
      const sdN1 = randomBytes(32);
      const sdN2 = randomBytes(32);

      sim.switchPlayer(p1Key, sdP1, sdN1);
      sim.commitBatch();
      sim.switchPlayer(p2Key, sdP2, sdN2);
      sim.commitBatch();
      sim.switchPlayer(p1Key, sdP1, sdN1);
      sim.revealBatch();
      sim.switchPlayer(p2Key, sdP2, sdN2);
      const sdState = sim.revealBatch();

      expect(sdState.phase).toEqual(Phase.COMPLETE);
      expect(sdState.winner).toEqual(sim.getLedger().player2);
    });
  });
});
