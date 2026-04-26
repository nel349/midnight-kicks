// Utility functions for penalty contract tests

import { randomBytes as nodeRandomBytes } from "crypto";

export function randomBytes(length: number): Uint8Array {
  return new Uint8Array(nodeRandomBytes(length));
}

// Direction constants — match the contract's Uint<8> encoding
export const LEFT = 0n;
export const CENTER = 1n;
export const RIGHT = 2n;

// Type alias for 5 direction choices
export type Choices = [bigint, bigint, bigint, bigint, bigint];
