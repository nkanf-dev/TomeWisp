# Phase 4I native settings deterministic verification

Date: 2026-07-18 (Asia/Shanghai)
Code commit under test: `a3ae197`

## Product gate

Command:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
```

Result: passed. The common test report contained 381 tests, 0 failures, 0
errors, and 2 skipped tests. Both loader modules built successfully.

Production artifacts:

- Fabric `tomewisp-fabric-26.2-0.1.0-SNAPSHOT.jar`:
  `3a33723813a10c53f51fe578d8f06365571c99b94fbd9f4af7309c3e62b6cc34`
- NeoForge `tomewisp-neoforge-26.2-0.1.0-SNAPSHOT.jar`:
  `bde2612a3bd3b8b97ab0092aad2cc58600c420314730dc2c857b463ead5150b3`

The packaged SQLite 3.50.3 driver was load-tested from both artifacts. Its
packaged SHA-256 values were
`0bc822a176492a4d3e2547b13a54bdf8540ea4e3a8ede8edc2d02f9a94c3c12a`
for Fabric and
`a3f53a2aa15ae9425a9e793bbe9c8e5288febeb4b65ef5c1a4e80d4c2045cf08`
for NeoForge. Linux x86_64/aarch64, macOS x86_64/aarch64, and Windows
x86_64/aarch64 natives were present.

## Static and security checks

- `git diff --check` passed.
- Every `scripts/*.sh` file passed `bash -n`.
- Every `scripts/*.py` file passed `python3 -m py_compile`.
- All nine repository JSON source files parsed with `jq`.
- Both production JARs passed the generic long `sk-` and bearer credential
  pattern scan.
- Common-code architecture tests found no Fabric or NeoForge imports.
- Both loader entrypoints construct the same display runtime, settings runtime,
  recipe runtime, ordered history repository, and one-time Guide history
  binding, and close their asynchronous owners in the same order.
- Durable history accepts only the current pre-release schema version 3. The
  production history package contains no migration or `ALTER TABLE` path;
  unsupported versions fail closed until explicit development reset.

## Manual/live status

The consolidated graphical settings acceptance was not run in this checkpoint;
it remains part of the final Phase 4 client audit. No live provider probe was
sent because `TOMEWISP_API_KEY` was absent from the launched verification
environment. No credential was copied from conversation into a file, command,
report, or artifact.
