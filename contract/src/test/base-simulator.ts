// Base simulator — shared logic for both fixed and vulnerable contracts.

import {
  type CircuitContext,
  QueryContext,
  sampleContractAddress,
  createConstructorContext,
  CostModel,
  Contract,
} from "@midnight-ntwrk/compact-runtime";
import {
  type PenaltyPrivateState,
  witnesses,
} from "../witnesses.js";
import { type Choices } from "./utils.js";

export interface PenaltyContractModule {
  Contract: new (w: typeof witnesses) => Contract<PenaltyPrivateState>;
  ledger: (state: any) => any;
}

// Default deadline far in the future (no timeout during tests)
const FAR_FUTURE = 9999999999n;

export class BasePenaltySimulator {
  readonly contract: Contract<PenaltyPrivateState>;
  circuitContext: CircuitContext<PenaltyPrivateState>;
  private readonly ledgerFn: (state: any) => any;
  private readonly hasDeadlineConstructor: boolean;

  constructor(
    contractModule: PenaltyContractModule,
    secretKey: Uint8Array,
    choices: Choices,
    nonce: Uint8Array,
    deadlineSecs: bigint = FAR_FUTURE,
  ) {
    this.contract = new contractModule.Contract(witnesses);
    this.ledgerFn = contractModule.ledger;

    // V2 constructor takes deadlineSecs, V1 doesn't
    // Try with deadline first, fall back to no-arg
    let initResult;
    try {
      initResult = this.contract.initialState(
        createConstructorContext(
          { secretKey, choices, nonce },
          "0".repeat(64),
        ),
        deadlineSecs,
      );
      this.hasDeadlineConstructor = true;
    } catch {
      initResult = this.contract.initialState(
        createConstructorContext(
          { secretKey, choices, nonce },
          "0".repeat(64),
        ),
      );
      this.hasDeadlineConstructor = false;
    }

    const {
      currentPrivateState,
      currentContractState,
      currentZswapLocalState,
    } = initResult;

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

  getLedger() {
    return this.ledgerFn(this.circuitContext.currentQueryContext.state);
  }

  getPrivateState(): PenaltyPrivateState {
    return this.circuitContext.currentPrivateState;
  }

  joinMatch(commitDeadlineSecs: bigint = FAR_FUTURE) {
    if (this.hasDeadlineConstructor) {
      this.circuitContext = this.contract.impureCircuits.joinMatch(
        this.circuitContext,
        commitDeadlineSecs,
      ).context;
    } else {
      this.circuitContext = this.contract.impureCircuits.joinMatch(
        this.circuitContext,
      ).context;
    }
    return this.getLedger();
  }

  commitBatch() {
    this.circuitContext = this.contract.impureCircuits.commitBatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  revealBatch() {
    this.circuitContext = this.contract.impureCircuits.revealBatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  claimTimeout() {
    if (!('claimTimeout' in this.contract.impureCircuits)) {
      throw new Error("claimTimeout not available in this contract version");
    }
    this.circuitContext = (this.contract.impureCircuits as any).claimTimeout(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }

  cancelMatch() {
    if (!('cancelMatch' in this.contract.impureCircuits)) {
      throw new Error("cancelMatch not available in this contract version");
    }
    this.circuitContext = (this.contract.impureCircuits as any).cancelMatch(
      this.circuitContext,
    ).context;
    return this.getLedger();
  }
}
