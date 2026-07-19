# Phase 4 Model Boundary Acceptance

Date: 2026-07-19

Target: Minecraft 26.2, Java 25

This directory retains redacted graphical-client proof for the final model
ownership and reverse player Tool bridge correction. The deterministic
loopback model fixture ran inside normal Fabric and NeoForge development
clients. Both clients joined disposable single-player worlds while the Agent
itself used the server-hosted model topology.

## Result

| Loader | Topology | Outcome | Tool chronology | Total time |
| --- | --- | --- | --- | --- |
| Fabric | `SERVER` | `COMPLETED` | 8 Tool invocations, 9 assistant segments | 4773 ms |
| NeoForge | `SERVER` | `COMPLETED` | 8 Tool invocations, 9 assistant segments | 4787 ms |

In both reports the server-hosted Agent called the requesting player's
`tomewisp:inspect_game_state` Tool for `OVERVIEW`, `MODS`, `OPTIONS`, `PACKS`,
`SHADERS`, `DIAGNOSTICS`, and `PLAYER`; every client-visible section succeeded.
The final `WORLD_QUERY` stayed server-authoritative and returned
`permission_denied` in the non-operator single-player test. That failure was
returned to the model as a structured Tool result, after which the request
continued to a final answer and `COMPLETED` instead of terminating.

The reports contain hashes, transitions, evidence summaries, Tool IDs and
status codes only. They contain no prompt body, normalized Tool result, API
key, authorization header, endpoint, or model reasoning.

## Command shape

```bash
TOMEWISP_E2E_MODEL_MODE=server \
TOMEWISP_E2E_QUESTION='TomeWisp E2E 服务端模型反向工具验收：依次读取所有八个分区，并在无权限查询时继续回答。' \
TOMEWISP_E2E_SCENARIO='phase-4-server-client-tools' \
TOMEWISP_E2E_QUICK_PLAY_WORLD='<disposable world>' \
TOMEWISP_E2E_SHUTDOWN=true \
TOMEWISP_E2E_REPORT='<ignored report path>' \
./scripts/run-real-client-e2e.sh fabric  # repeat with neoforge
```

The harness also verified that its temporary profile no longer collects the
ordinary development profile's local credential: the SQLite database triplet
is backed up before launch and restored after the client exits.

The rich Markdown, dynamic component, streaming stability, Tool detail and
narrow/wide screenshots remain retained in
`docs/verification/phase-4-final-corrections/` for both loaders.

## Production gate

```text
./gradlew clean :common:test :fabric:build :neoforge:build
BUILD SUCCESSFUL
562 tests, 0 failures, 0 errors, 3 opt-in skips

./scripts/verify-phase4-package.sh
phase4_package_verification=passed

./scripts/verify-sqlite-packaging.sh
SQLite 3.50.3 loaded from both production jars
native targets: Linux x86_64/aarch64, macOS x86_64/aarch64,
Windows x86_64/aarch64
```

The user-supplied live provider was also retried through the ignored local
credential database. The final attempt returned the stable redacted
`model_catalog_auth_failed` result (HTTP 401), including for a minimal inference
probe, so this record does not claim a current live-provider pass. No endpoint,
credential, header, or response body was retained.

## Retained hashes

```text
8a96a09ab0b9155482df85f946ecdcdfa18a31566cdaa2e6f84b0b7f88554537  fabric-server-client-tools-report.json
046e962df3332e83d52ff34abae098d640d1c9d97604f5f9eea148fd352244c8  neoforge-server-client-tools-report.json
f73d48ab34791c2547316ade4ae9f8629c89499205b2003016104d2bb65af6d0  tomewisp-fabric-26.2-0.1.0-SNAPSHOT.jar
5b47e94ca7c00e721965dd08ab3d893b5c438083587519f1a82e7ad055193bf7  tomewisp-neoforge-26.2-0.1.0-SNAPSHOT.jar
```
