# Phase 4 Native UX Final Acceptance

Date: 2026-07-19

Target: Minecraft 26.2, Java 25, Fabric and NeoForge

This directory retains the final graphical acceptance evidence for the Phase 4
native UI/UX correction set. Both runs used the repository's deterministic
loopback model fixture and an ephemeral local credential. No live or billable
model provider was contacted. The retained reports exclude prompts, normalized
Tool payloads, authorization headers, API keys, model configuration, and model
reasoning.

## Product priority and fallback boundary

The final pass prioritizes the complete conversation and settings experience:
stable streaming layout, explicit progress, correct selected-card identity,
friendly and debug Tool details, model selection, copy, safe export, and
double-confirmed deletion. Exact embedded recipe-viewer UI remains an optional
enhancement when a compatible viewer is installed. The deterministic generic
recipe view remains the fallback, but a viewer-free graphical profile was not
treated as a release-critical acceptance path because real modpacks with broad
recipe content normally include JEI or REI.

The Fabric profile included JEI, REI, and Farmer's Delight. Its retained top
view demonstrates the exact JEI-backed Farmer's Delight cooking-pot layout.
The NeoForge profile included JEI and Cooking for Blockheads and was used for
the outer game-state and settings UX. Artifact URLs, versions, SHA-256 values,
and honest compatibility limits are retained in
`../phase-4-final-corrections/README.md` and
`../phase-4c-all-known-recipes/README.md`.

## Graphical results

| Loader | Scenario | Outcome | Main assertions |
| --- | --- | --- | --- |
| Fabric | `phase-4-semantic-history` | `COMPLETED` | Five successful Tool calls in chronology, 32 semantic blocks, all eight controlled component types, table/list rendering, embedded JEI recipe view, model dropdown, wide/narrow detail layouts, and detailed Tool settings |
| NeoForge | `phase-4-game-state` | `COMPLETED` | Eight `inspect_game_state` calls, nine assistant segments, seven client-visible sections succeeded, and the command-permission-gated world query returned structured `permission_denied` without interrupting the Agent |

The final NeoForge detail screenshot selects a terminal `PLAYER` invocation
from the latest request. The selected card is highlighted and the side panel
shows the matching result, proving that durable older requests no longer steal
selection identity.

Each loader directory contains:

- `00-active-progress.png`: fixed progress strip during an active request;
- `01-wide-top.png`, `02-wide-middle.png`, `03-wide-final.png`: stable
  transcript anchors and final rich content;
- `04-wide-tool-detail.png`: selected-card highlight and populated detail;
- `05-wide-model-selector.png`: explicit model dropdown;
- `06-narrow-tool-detail.png`: responsive narrow overlay;
- `07-wide-tool-settings.png`, `08-wide-tool-settings-lower.png`: Tool
  master/detail configuration, parameters, return schema, and lower cards;
- `report.json`: redacted runtime state, chronology, evidence summaries, and
  metrics.

Report hashes:

```text
0dd24ef0f805334d287ef053861473845eb8c6fe7284ffdbbba5a59eb724dace  fabric/report.json
2a6ecbc0b912113678ecb7bd0341ab84c7b6ac81e4f4f204426a786edfbf178d  neoforge/report.json
```

## Session-action verification

The graphical frames retain the copy and export affordances. Destructive and
filesystem semantics are covered deterministically rather than automated by a
screen click:

- deletion is bound to a captured session ID and requires two native
  confirmation screens before cancellation/fencing, durable deletion, and UI
  removal;
- export paginates the complete durable session without changing the active
  viewport, writes only a closed player-facing DTO, redacts credential-shaped
  text, rejects symlink escapes, fsyncs a temporary file, and publishes it
  atomically under `gameDir/tomewisp/exports`;
- copy is available only for visible user and assistant text and reports
  clipboard failure without exposing internal data.

The owning state-machine decision is
`../../isme/decisions/2026-07-19-023-session-actions-and-safe-export.md`.

## Commands

The retained graphical runs were produced with the same repository harness used
by the acceptance controller. Environment values below contain no credential:

```bash
TOMEWISP_E2E_QUICK_PLAY_WORLD='New World' \
TOMEWISP_E2E_RECIPE_OUTPUT='farmersdelight:apple_cider' \
TOMEWISP_E2E_RECIPE_ID='farmersdelight:cooking/apple_cider' \
TOMEWISP_E2E_RECIPE_LABEL='苹果酒' \
TOMEWISP_E2E_SHUTDOWN_AFTER_SCREENSHOTS=true \
./scripts/run-real-client-e2e.sh fabric

TOMEWISP_E2E_QUICK_PLAY_WORLD='TomeWispNeoForgeSmoke' \
TOMEWISP_E2E_SCENARIO='phase-4-game-state' \
TOMEWISP_E2E_QUESTION='TomeWisp E2E 游戏外层状态验收：请依次读取运行概览、模组、设置、包、光影、诊断、玩家和只读世界查询。' \
TOMEWISP_E2E_SHUTDOWN_AFTER_SCREENSHOTS=true \
./scripts/run-real-client-e2e.sh neoforge
```

## Final deterministic gate

```text
./gradlew clean :common:test :fabric:build :neoforge:build
BUILD SUCCESSFUL
606 tests, 0 failures, 0 errors, 3 skipped opt-in tests

./scripts/verify-phase4-package.sh
phase4_package_verification=passed

./scripts/verify-sqlite-packaging.sh
SQLite 3.50.3 loaded from both production jars
native targets: Linux x86_64/aarch64, macOS x86_64/aarch64,
Windows x86_64/aarch64
```

Production artifacts from this gate:

| Artifact | SHA-256 |
| --- | --- |
| `tomewisp-fabric-26.2-0.1.0-SNAPSHOT.jar` | `aa514fc2f49037534445fa6c1baef6a699525fc78f0c3416faa42510b157b142` |
| `tomewisp-neoforge-26.2-0.1.0-SNAPSHOT.jar` | `ebf3c8bf4db3b95c576d9e48f33a32bf11dec48e978abbbe3a837801fb0bcce2` |

`git diff --check`, language/report JSON parsing, Bash syntax, and Python
fixture syntax also passed. Expected Java native-access, existing Javadoc, and
NeoForge compile-only Fabric annotation warnings remained non-fatal.
