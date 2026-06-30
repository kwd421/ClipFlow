# AGENTS.md

Repository guidance for Codex and other AI agents.

The goal is not to make the agent look busy. The goal is to keep the codebase
easy for the next AI session to understand, change, and verify.

## 1. Preserve The Shape Of The System

- Read the existing module boundaries before editing.
- Put new logic next to the behavior it belongs to; do not create a helper file
  just because a helper file sounds tidy.
- Prefer explicit data fields over hidden coupling.
- Keep row dictionaries, candidate dictionaries, worker events, and UI state
  shapes stable unless the user explicitly asks to change the contract.
- When adding a new field, make its producer and consumer obvious in the same
  change.

## 2. Verify Real Evidence First

- Inspect the real file, diff, command output, or app behavior before explaining
  a cause.
- Do not treat estimates, labels, cached metadata, or UI text as facts until
  verified.
- If terminal output or filenames look mojibake/broken, fix the shell/output
  encoding before using that output as evidence.
- For media/download issues, keep these concepts separate:
  protocol/transport, container, codec, bitrate, selected format, estimated size,
  and actual file size.

## 3. Make Small, Traceable Changes

- Touch only the files needed for the current request.
- Do not refactor adjacent code unless the requested fix cannot be made safely
  without it.
- Do not add abstractions for one-off fixes.
- Do not add fallback paths to hide a root cause.
- Every changed line should have a clear reason tied to the user's request.

## 4. Optimize For The Next AI Reader

- Use clear names over cleverness.
- Keep functions short enough that an agent can understand inputs, side effects,
  and outputs without reconstructing the whole app.
- Prefer one direct code path over several fallback branches.
- Leave a short comment only when it explains a boundary, invariant, or
  non-obvious external behavior.
- Do not rewrite working code just to make it look cleaner.

## 5. Tests Are Proportional, Not Automatic

- Do not add new tests for trivial constants, label changes, one-line config
  changes, or obvious UI text edits.
- Prefer the cheapest useful verification: compile, direct function check, real
  app check, or a focused command.
- Add or update tests only when the change affects shared behavior, fixes a
  realistic recurring regression, or the user asks for tests.
- If a test would be larger than the code change, pause and justify it before
  writing it.

## 6. Debug Without Guessing

- Find the root cause before changing code.
- Reproduce or gather direct evidence when practical.
- Change one thing at a time.
- If a fix fails, do not stack another guess on top of it; re-check the evidence.

## 7. Report What Actually Happened

- Say what was checked.
- Say what was not checked.
- Separate actual values from estimates.
- Keep final reports short unless the user asks for detail.
