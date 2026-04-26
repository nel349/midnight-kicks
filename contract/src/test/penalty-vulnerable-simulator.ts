// Simulator for the VULNERABLE version of penalty.compact
// Used by security.test.ts to reproduce exploits

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
} from "../managed/penalty-vulnerable/contract/index.js";
import {
  type PenaltyPrivateState,
  witnesses,
} from "../witnesses.js";
import { type Choices } from "./utils.js";

/**
 * Simulator using the VULNERABLE contract (pre-fix).
 * Contains VULN-002 (choices disclosed during commit) and
 * VULN-003 (secret key disclosed).
 *
 * Same API as PenaltySimulator — the tests show identical
 * behavior, proving the vulnerabilities exist in the circuit.
 */
export class VulnerablePenaltySimulator {
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

  getLedger(): Ledger {
    return ledger(this.circuitContext.currentQueryContext.state);
  }

  getPrivateState(): PenaltyPrivateState {
    return this.circuitContext.currentPrivateState;
  }

  joinMatch(): Ledger {
    this.circuitContext = this.contract.impureCircuits.joinMatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  commitBatch(): Ledger {
    this.circuitContext = this.contract.impureCircuits.commitBatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  revealBatch(): Ledger {
    this.circuitContext = this.contract.impureCircuits.revealBatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }
}
