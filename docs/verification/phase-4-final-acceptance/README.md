# Earlier Phase 4 Acceptance Evidence

This directory retains the first Phase 4 acceptance attempt on the Minecraft
26.2 / Java 25 main line. It does **not** close the current Phase 4 correction
set. The Fabric run used Architectury 21.0.2; a later normal walkthrough proved
that version's screen-input delegate can freeze TomeWisp text entry. It also
predates the masked local-credential workflow, separate Tools/Skills pages,
Tool-owned source configuration, external Agent Skills overrides, and
recognized pre-release history rebuild.

The retained graphical runs used the deterministic loopback OpenAI-compatible
fixture, not a billable provider, and remain valid evidence for the runtime
behaviors they actually exercised. They are historical evidence, not proof of
the later manual-acceptance corrections.

## Earlier Retained Result

Both graphical development clients entered a real single-player world and
completed the semantic-history scenario with outcome `COMPLETED`. Each final
request retained six assistant segments interleaved with five tool entries,
three validated controlled components (`status_badge`, `recipe_grid`, and
`progress_steps`), and one readable `semantic_component_unsupported` fallback.
The five grounded tools were recipe search, exact recipe lookup, inventory,
deterministic craftability, and knowledge-source listing.

The long-history NeoForge run created 48 seed requests before its main request.
That process reported 50 durable requests. A new graphical client process then
completed one more request and reported:

```text
loadedRequests=1
totalRequests=51
hasEarlier=1
hasLater=0
```

The restart therefore restored partition/session metadata and the current
window without hydrating the complete transcript. No product-wide request or
history cap was introduced.

## Loader Profiles and Compatibility Boundaries

The historical Fabric profile retained the previously installed Farmer's
Delight Refabricated runtime; it was not absent. It used JEI and REI together,
and the earlier Phase 4C retained screenshots prove TomeWisp-to-JEI exact
navigation to the Farmer's Delight apple-cider cooking page. That profile used
Architectury 21.0.2 and therefore does not prove text-entry acceptance.

The NeoForge profile used JEI and Cooking for Blockheads as its recipe-rich
sample. REI 26.2.820 was preflighted but displayed an upstream loading warning
that its `@OnlyIn` member-stripping behavior is no longer present. The accepted
profile therefore disables REI on NeoForge. This evidence does not call the
warning a TomeWisp or Farmer's Delight failure and does not claim NeoForge REI
compatibility.

Public 26.2 artifact checks found no EMI or Patchouli runtime for either target
loader. Patchouli support is therefore limited to TomeWisp's resource-based
parser/visibility/locale/multiblock tests and the `patchouli:resources`
knowledge-source evidence in these reports. No runtime Patchouli or EMI claim
is made.

| Loader | Accepted runtime artifacts | Result |
| --- | --- | --- |
| Fabric | JEI 30.12.0.69, REI 26.2.820, Farmer's Delight Refabricated 26.2-3.6.7 | Completed; exact JEI navigation is retained in the Phase 4C evidence |
| NeoForge | NeoForge 26.2.0.25-beta, JEI 30.12.0.72, Cooking for Blockheads 26.2.0.2, Balm 26.2.0.3 | Completed; REI explicitly disabled after its upstream warning |

## Artifact Provenance

Runtime JARs remained in ignored development directories. The values below
were computed from the exact files used or preflighted.

| Artifact | Direct source URL | SHA-256 |
| --- | --- | --- |
| Farmer's Delight Refabricated 26.2-3.6.7 | [Modrinth CDN](https://cdn.modrinth.com/data/7vxePowz/versions/BD1gWJYb/FarmersDelight-26.2-3.6.7%2Brefabricated.jar) | `a44afff2c18eae5caaf8b455525cfdd704ca5bd6c5ff4bc6a1f5bf24aaa10df2` |
| NeoForge JEI 30.12.0.72 | [Modrinth CDN](https://cdn.modrinth.com/data/u6dRKJwZ/versions/xjxJl8Z1/jei-26.2-neoforge-30.12.0.72.jar) | `349cc85b2267e8813701826c4dd5dc3bb14c5580d71728baa868477a2f36f5c4` |
| NeoForge REI 26.2.820 (preflighted, disabled) | [Modrinth CDN](https://cdn.modrinth.com/data/nfn13YXA/versions/BoY0Dky0/RoughlyEnoughItems-26.2.820.jar) | `d46284ac4d8b0d714cba03f62178ab57d8ca6805acf700855db0ab1e84dfebad` |
| NeoForge Architectury 21.0.4 | [Modrinth CDN](https://cdn.modrinth.com/data/lhGA9TYQ/versions/LKQeKupY/architectury-neoforge-21.0.4.jar) | `b767927106fe0d6a7e373b40ccffd510d132509fd5bf05aef4512023a62d17b8` |
| NeoForge Cloth Config 26.2.155 | [Modrinth CDN](https://cdn.modrinth.com/data/9s6osm5g/versions/zErG1kOw/cloth-config-26.2.155.jar) | `650cdc79fcb3332dd08d75634443b93b012dba49b748359a4a97f0975bc55c31` |
| Cooking for Blockheads 26.2.0.2 | [Modrinth CDN](https://cdn.modrinth.com/data/vJnhuDde/versions/bhljV0qf/cookingforblockheads-neoforge-26.2-26.2.0.2.jar) | `5b412cd34572a6d29cc79d8af9e0b15aa250f4fb72540bc1819874a87b3f4576` |
| Balm 26.2.0.3 | [Modrinth CDN](https://cdn.modrinth.com/data/MBAkmtvl/versions/l5LcttiY/balm-neoforge-26.2-26.2.0.3.jar) | `7e490a2ceb0033d3ba41ad143cbd7a16e41e96c28b5e02c590f1d3925a099e4c` |

Historical Fabric JEI/REI/Fabric API/Architectury/Cloth artifact URLs and hashes
are retained in `../phase-4c-all-known-recipes/README.md`. The Architectury
21.0.2 row is retained for provenance only and is explicitly incompatible with
the corrected text-entry profile. The corrected Fabric profile uses
Architectury 21.0.4 (`87ee5f2d28252dc249e7c269973891dd0b0dc208a937ca54778aa2b2287abfcb`).

## Phase 4 Claim Matrix

| Work package | Accepted claim | Evidence |
| --- | --- | --- |
| 4A chronology | Assistant/tool segments retain actual order and invocation identity | Both final reports' `timelineKinds`; timeline/protocol/reducer tests |
| 4B durable history | Partitioned persistence, interrupted recovery, no automatic resend | History repository/service/recovery tests; graphical restart report |
| 4C recipes | `ALL_KNOWN`, independent JEI/REI sources, exact navigation, sample-mod recipes | Phase 4C report/screenshots; final Fabric/NeoForge reports; compatibility table above |
| 4D context | Provider-neutral reduction/checkpoints respect the selected model budget and preserve evidence boundaries | Context reducer/checkpoint/topology tests; `CONTEXT_LOADING` real-client transitions |
| 4E presentation | Normal mode uses friendly cards; technical evidence/JSON remains Debug-only and redacted | Card/detail/debug-policy tests; retained Phase 4C TomeWisp card screenshot |
| 4F profiles | Sessions can switch model profiles while retaining provider-neutral history; OpenRouter metadata is cached | Registry/selection/metadata/cache tests; isolated `models.json` real-client profile |
| 4G model settings | The earlier environment-reference profile CRUD and isolated connection-probe path ran on both loaders | Historical settings service/screen/connection-probe tests; both client entrypoints launched. This does not prove the later masked local-credential workflow. |
| 4H knowledge/capabilities | The earlier combined catalog, deny-only policy, and recipe-owned viewer settings were exercised | Historical capability/settings tests; final `list_knowledge_sources` tool and Patchouli evidence. This does not prove the later separate Tools/Skills and Tool-owned source UX. |
| 4I history/diagnostics | Actor-scoped deletion, Debug-only reset, busy/rollback/no-resurrection, friendly diagnostics | History administration/settings tests; graphical durable restart evidence |
| 4J semantics/windowing | Safe Markdown, references, controlled components, fallback, incremental persistence, paging, virtualization, anchors | Both semantic reports; 50-request seed and 51-request restart; parser/store/UI performance and race tests |

The graphical reports prove actual loader startup, mod discovery, world entry,
viewer readiness, grounded tool execution, semantic stream handling, durable
write/restart, and clean shutdown. Exact JEI navigation has retained manual
screenshots from Phase 4C. Wide/narrow layout, settings navigation and taxonomy,
keyboard/narration, animation-off behavior, interruption, destructive deletion
and reset confirmations, busy rejection, and late-completion suppression are
deterministic screen/service tests; they were not relabeled as manual clicks in
this final run.

## Provider and Security Truth

`TOMEWISP_API_KEY` was absent from the final launched environment, so the
optional real-provider probe was not run. No credential was reconstructed from
conversation or written to argv, config, reports, logs, screenshots, or Git.
The harness used a random process-local fixture key and restored the prior
`models.json` exactly on exit. All retained JSON contains hashes and redacted
classification data, not prompts, raw tool payloads, normalized developer
traces, provider bodies, or credentials.

## Historical Clean Gate

The final from-clean verification completed successfully:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
bash -n scripts/*.sh
python3 -m py_compile scripts/*.py
./scripts/verify-phase4-package.sh
git diff --check
```

The common suite reported 427 tests, zero failures, zero errors, and two
explicit opt-in skips. Both production loader builds and the Phase 4 package,
native-library, schema, JSON, and credential-pattern checks passed.

```text
Fabric   55fd8070032d9675d6c0f7f6b8f2b1944971c173de21ab3233190e3173fede2d
NeoForge 6fbad2c184700e843bd0d83b6369f61dafa225ac57d791a26151481a7b35b7e6
```

## Retained Reports

| File | Purpose | SHA-256 |
| --- | --- | --- |
| `fabric-report.json` | Fabric semantic/tool/runtime report | `30fed2dcec83ebfacb15dd36806f0ff71516eb6f5e10f849b73c67b22bc04901` |
| `neoforge-report.json` | NeoForge JEI/sample-mod semantic/tool/runtime report | `374c02b7e0fb3a703b291976292511f8cf79758734dca5e7dce9f9212a48a191` |
| `neoforge-history-seed-report.json` | 48 seeds plus main request, 50 durable requests | `229ea6fe1ce26c104ede35c311b2e8e80a23ca62468cfa50ff476c08239df250` |
| `neoforge-paged-restart-report.json` | New process, 1 loaded of 51 with earlier cursor | `d12054dd27c1d542369728b7ee6bb0a50389edafc64cf24a94fdc0569ad533a8` |

## Deferred and Unclaimed

Structure-to-Ponder generation, recursive technology production planning,
Minecraft old-version ports, formal distribution, and execution on Windows or
Linux remain outside Phase 4. No compatible EMI/Patchouli 26.2 runtime and no
working NeoForge REI run are claimed. These files document an earlier
pre-release acceptance attempt; current Phase 4 completion remains contingent
on the later manual-acceptance correction walkthrough.
