# EchoTrace device authentication: status and migration design

This describes what is deployed, what it does not protect, and how existing pairs can be upgraded
without breaking old apps. It does not claim full authentication.

## Deployed now
- Codes are 6 characters (A-Z2-9). There is no unpair endpoint. Anyone who knows a code can poll that slot.
- Retired codes: a few synthetic test codes that were exposed in old history are refused (403).
- Optional `X-Device-Token` (random per install, only the SHA-256 is stored):
  - Bound only at `/register` of a brand-new code (no device, connection or pending record).
  - Never bound for an existing slot by an ordinary request, so a code-knower cannot pre-claim a legacy slot and lock old apps out.
  - Once bound, every request for that code must carry the token.
  - Enforcement for tokenless requests is off (`ENFORCE_TOKENS` unset), so old apps keep working.

## Not protected
- Existing pairs (including the real ones) have no token. Their images can be read by anyone who knows the code.
- A token only protects a slot when both devices in a pair use it. A tokenless partner can still be polled by a code-knower.
- Two clients registering the same brand-new code at the same moment: KV is not atomic. The server reads back after writing
  and answers 409 to the loser, but this is best effort. A Durable Object (SQLite, free plan) would make the claim atomic.

## Migration for existing pairs (implemented, off by default, not used on real pairs yet)
1. Owner updates the app on the device (a release that sends the token; the app already registers on every sync).
2. The owner opens a short migration window for that one code: set `MIGRATE_CODES` to the code's SHA-256 (comma separated).
   Only listed codes can be bound while existing, and only once.
3. The updated app binds on its next sync. `/poll` then returns `secured:true` to authenticated requests, which confirms the bind.
4. Close the window (remove the hash). Repeat for the partner device.
Risk during the window: someone who knows that code and registers before the owner's device could bind first. The window is short,
it is limited to one code, and a wrong bind is visible (the owner's app gets 401), so the owner can rerun with a new code.
Needed from the owner: update the app on each device, nothing else.
