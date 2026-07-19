# Phase 4C All-Known Recipe Acceptance Evidence

This directory retains the reviewed Fabric graphical acceptance run from
2026-07-18. The run used Minecraft 26.2, Java 25.0.2, Fabric Loader 0.19.3,
TomeWisp `0.1.0-SNAPSHOT`, JEI, REI, and Farmer's Delight Refabricated in one
ignored development profile. No live/billable model provider was contacted;
the model endpoint was the deterministic loopback fixture.

## Result

The command below completed with outcome `COMPLETED` and total recorded Agent
time 1333 ms:

```bash
TOMEWISP_E2E_QUICK_PLAY_WORLD=TomeWispSmoke \
TOMEWISP_E2E_SHUTDOWN=false \
TOMEWISP_E2E_REPORT="$PWD/build/e2e/fabric-manual-navigation.json" \
scripts/run-real-client-e2e.sh fabric
```

The retained report records the six-tool chronology
`search_recipes → get_recipe → inspect_inventory → calculate_craftability →
search_recipes → get_recipe`, generation-bearing JEI evidence for vanilla
crafting and `farmersdelight:cooking`, and no raw prompts or tool payloads.
The fresh world's synchronized vanilla recipe-book provider contained only its
partial unlocked set, while the apple-cider result resolved through the
`viewer:jei` reference shown in the TomeWisp detail screenshot. The exact
reference then opened the Farmer's Delight cooking page in JEI, showing two
apples, sugar, a glass bottle, and apple-cider output.

This graphical run exposed and verified fixes for three acceptance-only
failures in code commit `5af5b4e`: loader-root JEI discovery/readiness, detail
actions being swallowed by panel dismissal/long diagnostics, and exact JEI
matching aborting on an earlier unsupported layout.

REI and JEI coexisted in the same client. REI emitted upstream display-fill
errors for the Farmer's Delight dumplings and cabbage-roll recipes; TomeWisp
kept the remaining sources operational and completed the scenario through JEI,
which is the independent partial-provider behavior required by SKMB-007.

## Installed Artifacts

The SHA-256 values were computed from the exact local JARs. The version and
download URL were resolved from the JAR SHA-1 through Modrinth's public v2
`version_files` endpoint with a named User-Agent.

| Artifact | Version | Direct source URL | SHA-256 |
| --- | --- | --- | --- |
| JEI | 30.12.0.69 | [jei-26.2-fabric-30.12.0.69.jar](https://cdn.modrinth.com/data/u6dRKJwZ/versions/Yb6CGw6E/jei-26.2-fabric-30.12.0.69.jar) | `23a1a30e70f5661c7c88e5ab5f7a5107339829164bd49b358c765a6be6528205` |
| Roughly Enough Items | 26.2.820 | [RoughlyEnoughItems-26.2.820.jar](https://cdn.modrinth.com/data/nfn13YXA/versions/4o0NSIMj/RoughlyEnoughItems-26.2.820.jar) | `9ba9d2a31531759549924064f33d3d45af342e7246f877efb8cee9f75f6ddaa9` |
| Farmer's Delight Refabricated | 26.2-3.6.7 | [FarmersDelight-26.2-3.6.7+refabricated.jar](https://cdn.modrinth.com/data/7vxePowz/versions/BD1gWJYb/FarmersDelight-26.2-3.6.7%2Brefabricated.jar) | `a44afff2c18eae5caaf8b455525cfdd704ca5bd6c5ff4bc6a1f5bf24aaa10df2` |
| Fabric API | 0.155.2+26.2 | [fabric-api-0.155.2+26.2.jar](https://cdn.modrinth.com/data/P7dR8mSH/versions/lVXlbH4w/fabric-api-0.155.2%2B26.2.jar) | `d6518c770024cbe8a556248f16fcdbb91c6a62f50227a6c3bae8190511e2c1b8` |
| Architectury API (historical provenance; incompatible with corrected TomeWisp text input) | 21.0.2 | [architectury-fabric-21.0.2.jar](https://cdn.modrinth.com/data/lhGA9TYQ/versions/OVFwpVeQ/architectury-fabric-21.0.2.jar) | `989f5937c9c42c033711684304a6d0d6c251e957770f9270ca01f2d1c98e67c7` |
| Cloth Config | 26.2.155 | [cloth-config-26.2.155.jar](https://cdn.modrinth.com/data/9s6osm5g/versions/Nv3xnWXd/cloth-config-26.2.155.jar) | `def4be7639cd66704f7e304d658ea0f6bf490fb4a6eaa2dbf18ec2c3999d6349` |

Public Modrinth queries filtered by game version `26.2` and loaders `fabric`
or `neoforge` returned no versions for Patchouli or EMI on 2026-07-18.
Accordingly, this run does not claim either runtime integration. TomeWisp's
resource-based Patchouli path was instead exercised by all three tests in
`PatchouliKnowledgeProviderTest`, including locale/page normalization, dense and
sparse multiblock extraction, visibility exclusion, and the read-tool
projection.

## Retained Files

| File | Purpose | SHA-256 |
| --- | --- | --- |
| `fabric-real-client-report.json` | Redacted E2E state/tool/evidence report | `497bbbaed66238dbbbdde1497a336f1446235335bc3a2f959c662824e3135a1f` |
| `fabric-client-redacted.log` | Minimal startup, viewer-registration, readiness, and sync excerpt | `96cb47e0c75e464bfd85c07ee02faf50bdfc95e85661436751a8674c278cdc14` |
| `tomewisp-apple-cider-card.png` | TomeWisp JEI-backed apple-cider card and successful-open notice | `1faf70bc6bf356b48e610c23c618590b0405cd8c8a63a65d100a1937fc513d43` |
| `jei-apple-cider-exact.png` | Exact Farmer's Delight apple-cider page opened in JEI | `4d6f6d64e6b35b617888b229c83178b73c92e5fc5670e98270b865ee3f7467ec` |

## Deterministic Gates

The focused provider/catalog/tool/UI/architecture suite passed first. The final
clean command then reported 189 common tests, 0 failures, 0 errors, and one
explicit opt-in skip, followed by successful Fabric and NeoForge builds:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
```

Production artifact hashes at this verification point:

```text
Fabric   9213e38882780e8ba2e09452682392e7a6c15158fe6ed408a99c596897b12396
NeoForge 00fc50c77c142d79f1c24a6f21cf904a48bcb6dad9cd59221e2b0da7f5b2e921
```

`git diff --check`, Bash syntax, Python fixture syntax, language/report JSON,
and tracked-file plus both production-JAR credential-pattern scans passed. The
expected Java 25 native-access, existing Javadoc, and NeoForge compile-only
Fabric annotation warnings remain non-fatal. NeoForge parity is proven by
common behavior tests and its production build; this retained graphical run is
Fabric-only and does not claim a NeoForge GUI launch.
