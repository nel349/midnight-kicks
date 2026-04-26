import type * as __compactRuntime from '@midnight-ntwrk/compact-runtime';

export enum Phase { WAITING = 0,
                    COMMITTING = 1,
                    REVEALING = 2,
                    SD_COMMITTING = 3,
                    SD_REVEALING = 4,
                    COMPLETE = 5
}

export type Witnesses<PS> = {
  localSecretKey(context: __compactRuntime.WitnessContext<Ledger, PS>): [PS, Uint8Array];
  localChoice0(context: __compactRuntime.WitnessContext<Ledger, PS>): [PS, bigint];
  localChoice1(context: __compactRuntime.WitnessContext<Ledger, PS>): [PS, bigint];
  localChoice2(context: __compactRuntime.WitnessContext<Ledger, PS>): [PS, bigint];
  localChoice3(context: __compactRuntime.WitnessContext<Ledger, PS>): [PS, bigint];
  localChoice4(context: __compactRuntime.WitnessContext<Ledger, PS>): [PS, bigint];
  localNonce(context: __compactRuntime.WitnessContext<Ledger, PS>): [PS, Uint8Array];
}

export type ImpureCircuits<PS> = {
  joinMatch(context: __compactRuntime.CircuitContext<PS>,
            commitDeadlineSecs_0: bigint): __compactRuntime.CircuitResults<PS, []>;
  commitBatch(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
  revealBatch(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
  claimTimeout(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
  cancelMatch(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
}

export type ProvableCircuits<PS> = {
  joinMatch(context: __compactRuntime.CircuitContext<PS>,
            commitDeadlineSecs_0: bigint): __compactRuntime.CircuitResults<PS, []>;
  commitBatch(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
  revealBatch(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
  claimTimeout(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
  cancelMatch(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
}

export type PureCircuits = {
}

export type Circuits<PS> = {
  joinMatch(context: __compactRuntime.CircuitContext<PS>,
            commitDeadlineSecs_0: bigint): __compactRuntime.CircuitResults<PS, []>;
  commitBatch(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
  revealBatch(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
  claimTimeout(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
  cancelMatch(context: __compactRuntime.CircuitContext<PS>): __compactRuntime.CircuitResults<PS, []>;
}

export type Ledger = {
  readonly phase: Phase;
  readonly player1: Uint8Array;
  readonly player2: Uint8Array;
  readonly p1Commitment: Uint8Array;
  readonly p2Commitment: Uint8Array;
  readonly p1Committed: boolean;
  readonly p2Committed: boolean;
  readonly p1c0: bigint;
  readonly p1c1: bigint;
  readonly p1c2: bigint;
  readonly p1c3: bigint;
  readonly p1c4: bigint;
  readonly p2c0: bigint;
  readonly p2c1: bigint;
  readonly p2c2: bigint;
  readonly p2c3: bigint;
  readonly p2c4: bigint;
  readonly p1Revealed: boolean;
  readonly p2Revealed: boolean;
  readonly p1Score: bigint;
  readonly p2Score: bigint;
  readonly winner: Uint8Array;
  readonly isDraw: boolean;
  readonly deadline: bigint;
  readonly sdRound: bigint;
}

export type ContractReferenceLocations = any;

export declare const contractReferenceLocations : ContractReferenceLocations;

export declare class Contract<PS = any, W extends Witnesses<PS> = Witnesses<PS>> {
  witnesses: W;
  circuits: Circuits<PS>;
  impureCircuits: ImpureCircuits<PS>;
  provableCircuits: ProvableCircuits<PS>;
  constructor(witnesses: W);
  initialState(context: __compactRuntime.ConstructorContext<PS>,
               deadlineSecs_0: bigint): __compactRuntime.ConstructorResult<PS>;
}

export declare function ledger(state: __compactRuntime.StateValue | __compactRuntime.ChargedState): Ledger;
export declare const pureCircuits: PureCircuits;
