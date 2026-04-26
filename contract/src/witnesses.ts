// Midnight Kicks — Witness implementations for penalty.compact
// SPDX-License-Identifier: Apache-2.0

import { Ledger } from "./managed/penalty/contract/index.js";
import { WitnessContext } from "@midnight-ntwrk/compact-runtime";

// ═══════════════════════════════════════════════════════════════════
// Private state shape
// ═══════════════════════════════════════════════════════════════════

// Each player's private state holds their secret key, 5 direction
// choices (0=LEFT, 1=CENTER, 2=RIGHT), and a commitment nonce.
export type PenaltyPrivateState = {
  readonly secretKey: Uint8Array;
  readonly choices: [bigint, bigint, bigint, bigint, bigint];
  readonly nonce: Uint8Array;
};

export const createPenaltyPrivateState = (
  secretKey: Uint8Array,
  choices: [bigint, bigint, bigint, bigint, bigint],
  nonce: Uint8Array,
): PenaltyPrivateState => ({
  secretKey,
  choices,
  nonce,
});

// ═══════════════════════════════════════════════════════════════════
// Witness implementations
// ═══════════════════════════════════════════════════════════════════

// Each witness function:
// - Takes a WitnessContext (ledger state + private state + contract address)
// - Returns [newPrivateState, returnValue]
// - Private state is carried forward unchanged (read-only witnesses)

export const witnesses = {
  localSecretKey: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    Uint8Array,
  ] => [privateState, privateState.secretKey],

  localChoice0: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    bigint,
  ] => [privateState, privateState.choices[0]],

  localChoice1: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    bigint,
  ] => [privateState, privateState.choices[1]],

  localChoice2: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    bigint,
  ] => [privateState, privateState.choices[2]],

  localChoice3: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    bigint,
  ] => [privateState, privateState.choices[3]],

  localChoice4: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    bigint,
  ] => [privateState, privateState.choices[4]],

  localNonce: ({
    privateState,
  }: WitnessContext<Ledger, PenaltyPrivateState>): [
    PenaltyPrivateState,
    Uint8Array,
  ] => [privateState, privateState.nonce],
};
