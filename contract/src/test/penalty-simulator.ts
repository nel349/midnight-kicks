// Penalty contract simulator — local circuit execution for testing
// Follows the same pattern as BBoard's BBoardSimulator

import {
  type CircuitContext,
  QueryContext,
  sampleContractAddress,
  createConstructorContext,
  CostModel,
} from "@midnight-ntwrk/compact-runtime";
import {
  Contract,
  type Ledger,
  ledger,
} from "../managed/penalty/contract/index.js";
import {
  type PenaltyPrivateState,
  witnesses,
} from "../witnesses.js";
import { type Choices } from "./utils.js";

/**
 * Simulates the penalty contract locally for testing.
 *
 * Executes circuits via compact-runtime without network, Docker,
 * or wallet. Pure local state transitions.
 *
 * Supports multi-player interaction via switchPlayer().
 */
export class PenaltySimulator {
  readonly contract: Contract<PenaltyPrivateState>;
  circuitContext: CircuitContext<PenaltyPrivateState>;

  constructor(
    secretKey: Uint8Array,
    choices: Choices,
    nonce: Uint8Array,
  ) {
    this.contract = new Contract<PenaltyPrivateState>(witnesses);
    const {
      currentPrivateState,
      currentContractState,
      currentZswapLocalState,
    } = this.contract.initialState(
      createConstructorContext(
        { secretKey, choices, nonce },
        "0".repeat(64),
      ),
    );
    this.circuitContext = {
      currentPrivateState,
      currentZswapLocalState,
      costModel: CostModel.initialCostModel(),
      currentQueryContext: new QueryContext(
        currentContractState.data,
        sampleContractAddress(),
      ),
    };
  }

  /** Switch to a different player's private state */
  switchPlayer(
    secretKey: Uint8Array,
    choices: Choices,
    nonce: Uint8Array,
  ): void {
    this.circuitContext.currentPrivateState = {
      secretKey,
      choices,
      nonce,
    };
  }

  /** Read current public ledger state */
  getLedger(): Ledger {
    return ledger(this.circuitContext.currentQueryContext.state);
  }

  /** Get current private state */
  getPrivateState(): PenaltyPrivateState {
    return this.circuitContext.currentPrivateState;
  }

  /** Player 2 joins the match */
  joinMatch(): Ledger {
    this.circuitContext = this.contract.impureCircuits.joinMatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  /** Submit commitment hash (computed from private choices + nonce) */
  commitBatch(): Ledger {
    this.circuitContext = this.contract.impureCircuits.commitBatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  /** Reveal choices and verify commitment match */
  revealBatch(): Ledger {
    this.circuitContext = this.contract.impureCircuits.revealBatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }
}
