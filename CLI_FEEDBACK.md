# `mn` CLI Feedback — from building Midnight Kicks

Friction encountered while developing and deploying a Compact
contract (penalty shootout game) using `mn` CLI v0.3.0.

---

## 1. `mn contract deploy` requires the full midnight-js SDK installed locally

**Severity:** HIGH — blocks any developer from deploying without
knowing the hidden dependency chain

**What happened:** `mn contract deploy --managed <path>` generates
a temp `.mjs` file that imports from `@midnight-ntwrk/midnight-js-*`
packages. These must be installed in the project's `node_modules`.
The CLI doesn't bundle them — the developer must figure out which
packages to install by reading error messages one at a time.

**Errors we hit (in order):**
1. `Cannot find package '@midnight-ntwrk/midnight-js-network-id'`
2. `Cannot find package 'ws'`
3. `Cannot find package '@midnight-ntwrk/wallet-sdk-address-format'`

**What we had to install** (copied from example-bboard):
```
@midnight-ntwrk/dapp-connector-api
@midnight-ntwrk/ledger-v8
@midnight-ntwrk/midnight-js-compact
@midnight-ntwrk/midnight-js-contracts
@midnight-ntwrk/midnight-js-types
@midnight-ntwrk/midnight-js-utils
@midnight-ntwrk/testkit-js
@midnight-ntwrk/wallet-sdk-address-format
@midnight-ntwrk/wallet-sdk-facade
@midnight-ntwrk/wallet-sdk-hd
ws
```

**Expected:** `mn contract deploy` should work with just the
compiled artifacts. The CLI should bundle its own deployment
dependencies, not require the developer's project to have them.

**Workaround:** Copy the full dependency list from example-bboard.

---

## 2. `mn contract deploy` doesn't support constructor arguments

**Severity:** HIGH — blocks deployment of any contract with a
parameterized constructor

**What happened:** Our constructor takes `deadlineSecs: Uint<64>`.
The deploy command has `--args` but it only works for `call`, not
`deploy`. The error:

```
Contract state constructor: expected 2 arguments (as invoked from
Typescript), received 1
```

There's no way to pass constructor arguments via the CLI.

**Expected:** `mn contract deploy --args '{"deadlineSecs": 300}'`
should work, same as `mn contract call --args`.

**Workaround:** We removed the constructor parameter and used a
no-arg constructor. The deadline is set later via `joinMatch`.

---

## 3. `--json` flag doesn't suppress spinner output

**Severity:** LOW — wastes tokens when used by AI agents / scripts

**What happened:** `mn contract deploy --json` still outputs ANSI
spinner frames (`⠋ Checking wallet...`, `⠙ Deploying contract...`)
before the JSON. The JSON appears at the end mixed with the spinner
output. This makes it hard to parse programmatically and wastes
context window tokens when used by AI agents.

**Expected:** `--json` should output ONLY the JSON result, no
spinner, no ANSI codes. Spinners should go to stderr or be
suppressed entirely in JSON mode.

---

## 4. Witness discovery requires a built index.js

**Severity:** MEDIUM — confusing when witnesses exist but aren't found

**What happened:** We had `witnesses.ts` in the right place but
`mn contract deploy` said "Warning: No witnesses found — using
vacant witnesses." The deploy script needs a compiled `index.js`
that exports the witnesses. The TS source isn't enough.

We had to:
1. Create `src/index.ts` that re-exports witnesses
2. Run `tsc` to compile to `dist/`
3. Ensure `package.json` has `"main": "./dist/index.js"`

**Expected:** Either `mn contract deploy` should look for
`witnesses.ts` directly (compile on the fly), or the error message
should say WHERE it's looking and WHAT format it expects.

---

## 5. `mn contract deploy` generates a temp .mjs that runs in the project context

**Severity:** LOW — surprising behavior, hard to debug

**What happened:** The deploy command generates
`.mn-contract-<timestamp>.mjs` in the current directory, then runs
it with `node`. This script imports from `node_modules` relative to
the project, not relative to the `mn` CLI installation. If you run
`mn contract deploy` from the wrong directory, you get confusing
`ERR_MODULE_NOT_FOUND` errors.

**Expected:** The generated script should resolve imports relative
to the `mn` CLI's own `node_modules`, not the developer's project.
Or at minimum, the error should say "run this command from your
project root" instead of a raw Node.js module resolution error.

---

## Environment

- `mn` CLI v0.3.0
- Compact compiler v0.30.0
- Node.js v25.3.0
- macOS (darwin)
- Contract: 5 circuits (joinMatch, commitBatch, revealBatch,
  claimTimeout, cancelMatch)
- Network: undeployed (localnet)
