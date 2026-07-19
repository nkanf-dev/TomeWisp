# Phase 4 Final Correction Acceptance

Date: 2026-07-19

Target: Minecraft 26.2, Java 25

Loaders: Fabric and NeoForge

This directory is the retained, redacted acceptance evidence for the final
Phase 4 correction set. The model side was the repository's deterministic
loopback OpenAI-compatible fixture. It used an ephemeral local credential and
made no billable provider request. Reports contain hashes, state transitions,
Tool IDs, Tool statuses, section names, evidence summaries, and semantic
metrics; they contain no prompt body, normalized Tool result, authorization
header, API key, or model reasoning.

## Result

All four graphical development-client runs completed successfully:

| Loader | Scenario | Outcome | Retained proof |
| --- | --- | --- | --- |
| Fabric | `phase-4-semantic-history` | `COMPLETED`; 6 assistant segments, 5 successful Tools, strict chronology, 32 semantic blocks, 5 safe fallbacks, all 8 controlled component types | `fabric-ui-report.json`, `fabric-ui/*.png` |
| NeoForge | `phase-4-semantic-history` | Same contract and counts | `neoforge-ui-report.json`, `neoforge-ui/*.png` |
| Fabric | `phase-4-game-state` | `COMPLETED`; 8 successful `inspect_game_state` calls, 9 assistant segments, 17 alternating timeline entries | `fabric-game-state-report.json` |
| NeoForge | `phase-4-game-state` | Same contract and counts | `neoforge-game-state-report.json` |

The game-state reports prove the exact ordered sections rather than merely
counting Tool IDs: `OVERVIEW`, `MODS`, `OPTIONS`, `PACKS`, `SHADERS`,
`DIAGNOSTICS`, `PLAYER`, and `WORLD_QUERY`; every probe is `SUCCEEDED`.

The six screenshots per loader prove:

- a fixed-height active progress strip with phase, elapsed time, and remaining
  request budget;
- stable top, middle, and final transcript anchors during streamed content;
- headings, emphasis, nested ordered/unordered lists, inline and fenced code,
  a table, Minecraft item references, and safe text fallbacks for rejected
  external links, images, HTML, and unsupported components;
- recipe/item/craftability/source components, friendly localized Tool cards,
  and an unclipped model display name;
- wide-screen side-panel and narrow-screen overlay Tool detail layouts.

Fabric was captured under Simplified Chinese. NeoForge kept an English locale
for player-facing card/status text, providing a second localization path while
the deterministic fixture content remained intentionally bilingual.

## Commands

The retained runs were produced with the repository harness and disposable
single-player worlds:

```bash
TOMEWISP_E2E_QUICK_PLAY_WORLD='New World' \
TOMEWISP_E2E_SHUTDOWN=false \
TOMEWISP_E2E_SHUTDOWN_AFTER_SCREENSHOTS=true \
TOMEWISP_E2E_SCREENSHOT_ROOT="$PWD/build/e2e/final2-fabric-ui" \
TOMEWISP_E2E_REPORT="$PWD/build/e2e/final2-fabric-ui.json" \
./scripts/run-real-client-e2e.sh fabric

TOMEWISP_E2E_QUICK_PLAY_WORLD='TomeWispNeoForgeClean' \
TOMEWISP_E2E_SHUTDOWN=false \
TOMEWISP_E2E_SHUTDOWN_AFTER_SCREENSHOTS=true \
TOMEWISP_E2E_SCREENSHOT_ROOT="$PWD/build/e2e/final2-neoforge-ui" \
TOMEWISP_E2E_REPORT="$PWD/build/e2e/final2-neoforge-ui.json" \
./scripts/run-real-client-e2e.sh neoforge

TOMEWISP_E2E_QUESTION='TomeWisp E2E 游戏外层状态验收：依次读取所有八个分区。' \
TOMEWISP_E2E_SCENARIO='phase-4-game-state' \
TOMEWISP_E2E_QUICK_PLAY_WORLD='New World' \
TOMEWISP_E2E_SHUTDOWN=true \
TOMEWISP_E2E_REPORT="$PWD/build/e2e/final2-fabric-game-state.json" \
./scripts/run-real-client-e2e.sh fabric

TOMEWISP_E2E_QUESTION='TomeWisp E2E 游戏外层状态验收：依次读取所有八个分区。' \
TOMEWISP_E2E_SCENARIO='phase-4-game-state' \
TOMEWISP_E2E_QUICK_PLAY_WORLD='TomeWispNeoForgeClean' \
TOMEWISP_E2E_SHUTDOWN=true \
TOMEWISP_E2E_REPORT="$PWD/build/e2e/final2-neoforge-game-state.json" \
./scripts/run-real-client-e2e.sh neoforge
```

The final clean production gate passed after these runs:

```text
./gradlew clean :common:test :fabric:build :neoforge:build
BUILD SUCCESSFUL
525 tests, 0 failures, 0 errors, 2 skipped

./scripts/verify-phase4-package.sh
phase4_package_verification=passed

./scripts/verify-sqlite-packaging.sh
SQLite 3.50.3 loads from both production jars
native targets: Linux x86_64/aarch64, macOS x86_64/aarch64,
Windows x86_64/aarch64
```

Production artifacts:

| Artifact | SHA-256 |
| --- | --- |
| `tomewisp-fabric-26.2-0.1.0-SNAPSHOT.jar` | `dc3af415f054cd553048a0cc872100cb1fce97d662a777cbb968e50bfe320ee9` |
| `tomewisp-neoforge-26.2-0.1.0-SNAPSHOT.jar` | `b5b4193fd402727c047ec652050a19722fa8f4adc9c53e0c7cc22986699f0954` |

## Runtime profile provenance

The following compatible target artifacts were installed only in ignored
development run directories. URLs and hashes were resolved through the
official Modrinth v2 API.

| Loader | Artifact | Version | SHA-256 | Download |
| --- | --- | --- | --- | --- |
| Fabric | Farmer's Delight Refabricated | 26.2-3.6.7 | `a44afff2c18eae5caaf8b455525cfdd704ca5bd6c5ff4bc6a1f5bf24aaa10df2` | [Modrinth CDN](https://cdn.modrinth.com/data/7vxePowz/versions/BD1gWJYb/FarmersDelight-26.2-3.6.7%2Brefabricated.jar) |
| Fabric | Roughly Enough Items | 26.2.820 | `9ba9d2a31531759549924064f33d3d45af342e7246f877efb8cee9f75f6ddaa9` | [Modrinth CDN](https://cdn.modrinth.com/data/nfn13YXA/versions/4o0NSIMj/RoughlyEnoughItems-26.2.820.jar) |
| Fabric | Architectury API | 21.0.4 | `87ee5f2d28252dc249e7c269973891dd0b0dc208a937ca54778aa2b2287abfcb` | [Modrinth CDN](https://cdn.modrinth.com/data/lhGA9TYQ/versions/QZBULxdg/architectury-fabric-21.0.4.jar) |
| Fabric | Cloth Config | 26.2.155 | `def4be7639cd66704f7e304d658ea0f6bf490fb4a6eaa2dbf18ec2c3999d6349` | [Modrinth CDN](https://cdn.modrinth.com/data/9s6osm5g/versions/Nv3xnWXd/cloth-config-26.2.155.jar) |
| Fabric | Fabric API | 0.155.2+26.2 | `d6518c770024cbe8a556248f16fcdbb91c6a62f50227a6c3bae8190511e2c1b8` | [Modrinth CDN](https://cdn.modrinth.com/data/P7dR8mSH/versions/lVXlbH4w/fabric-api-0.155.2%2B26.2.jar) |
| Fabric | Just Enough Items | 30.12.0.69 | `23a1a30e70f5661c7c88e5ab5f7a5107339829164bd49b358c765a6be6528205` | [Modrinth CDN](https://cdn.modrinth.com/data/u6dRKJwZ/versions/Yb6CGw6E/jei-26.2-fabric-30.12.0.69.jar) |
| NeoForge | Architectury API | 21.0.4 | `b767927106fe0d6a7e373b40ccffd510d132509fd5bf05aef4512023a62d17b8` | [Modrinth CDN](https://cdn.modrinth.com/data/lhGA9TYQ/versions/LKQeKupY/architectury-neoforge-21.0.4.jar) |
| NeoForge | Balm | 26.2.0.3 | `7e490a2ceb0033d3ba41ad143cbd7a16e41e96c28b5e02c590f1d3925a099e4c` | [Modrinth CDN](https://cdn.modrinth.com/data/MBAkmtvl/versions/l5LcttiY/balm-neoforge-26.2-26.2.0.3.jar) |
| NeoForge | Cloth Config | 26.2.155 | `650cdc79fcb3332dd08d75634443b93b012dba49b748359a4a97f0975bc55c31` | [Modrinth CDN](https://cdn.modrinth.com/data/9s6osm5g/versions/zErG1kOw/cloth-config-26.2.155.jar) |
| NeoForge | Cooking for Blockheads | 26.2.0.2 | `5b412cd34572a6d29cc79d8af9e0b15aa250f4fb72540bc1819874a87b3f4576` | [Modrinth CDN](https://cdn.modrinth.com/data/vJnhuDde/versions/bhljV0qf/cookingforblockheads-neoforge-26.2-26.2.0.2.jar) |
| NeoForge | Just Enough Items | 30.12.0.72 | `349cc85b2267e8813701826c4dd5dc3bb14c5580d71728baa868477a2f36f5c4` | [Modrinth CDN](https://cdn.modrinth.com/data/u6dRKJwZ/versions/xjxJl8Z1/jei-26.2-neoforge-30.12.0.72.jar) |

## Honest compatibility boundaries

- Fabric REI started and published a non-empty catalog, but its Farmer's
  Delight plugin logged upstream display-fill errors for two cooking recipes.
  JEI and unaffected source paths remained usable; the error is not presented
  as full REI compatibility for those recipes.
- NeoForge REI was excluded after its current 26.2 artifact emitted the loader
  `@OnlyIn` warning during prior compatibility testing. NeoForge acceptance used
  JEI plus a compatible recipe-rich mod.
- No compatible Minecraft 26.2 EMI or Patchouli runtime artifact was available
  for this profile. Patchouli's resource-based parser remains independently
  tested and reports explicit availability rather than fabricating a runtime.
- No verified public 26.2 shader adapter was installed, so `SHADERS` correctly
  returned explicit `UNKNOWN` evidence instead of guessing or using unrestricted
  reflection.

## Retained file hashes

```text
bce5fd0544193214c8a953e3c90c325ac836158fa9384da54def46b5f1932c8d  fabric-game-state-report.json
33fafe2ac9402c4cea9b2329d225c8d4d204fe00ca8468cb6f269744134a600a  fabric-ui-report.json
287751b0ab969f9ef823df4812109a81eff4bd3cfa7cccd2bcd67ea9207ac396  neoforge-game-state-report.json
d13696e795b2e351aa8c0eba76ea76442ab116bece8eaab4cd047694c12013aa  neoforge-ui-report.json
41699809eb028b20a6d0ccef67a443d3e101c83b3c9f0ddbeb766143446ee49e  fabric-ui/00-active-progress.png
f9111609ac9d220199f882b887d11c07bedce7da662e47070aded65da3b9e534  fabric-ui/01-wide-top.png
8eb42751f3f739dd97b609e6d95b85c23cf6a0cbb32ba660b79bc038c415f16a  fabric-ui/02-wide-middle.png
d820f533d1972b5623653a18ea19903a82b3adc87d30c4943d6a4937c54f8252  fabric-ui/03-wide-final.png
de32211a5d74fab689c79ad7b4c36659623f1b47b22229b526a6add7b1d69f08  fabric-ui/04-wide-tool-detail.png
53242f948b8e6417c900804abf9ae57c23b827b26fd39284665b7ad66e8b22c4  fabric-ui/05-narrow-tool-detail.png
9f218712a8a0279b6062c5eae7459822473c8f02e7b2d8767fa0fa5696fa0361  neoforge-ui/00-active-progress.png
9c7c3e5550d94ac22c16ecfe668b5e269e5cd36d98a1eb6074083fb1d3d19de8  neoforge-ui/01-wide-top.png
43009570c7ca69444c29991191f1c5b320d23cf7578caee91e091ebe544fada8  neoforge-ui/02-wide-middle.png
4662f8d9fa4284bc0a602ab7b04618d157841632bfaf9e1bde2d75b2817cc86a  neoforge-ui/03-wide-final.png
2c2beb9304540b187d4e39ce0beeb18ce8c4ac2c391b157fe5f757fc5f818ac3  neoforge-ui/04-wide-tool-detail.png
267b5657c2011f1022acfadc06a0b3f17e6736a92ef8980276e7442d9579e371  neoforge-ui/05-narrow-tool-detail.png
```
