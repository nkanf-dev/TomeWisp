# OpenAllay Product Rename Design

Status: approved for documentation on 2026-07-19. Implementation is pending
and must follow this design as one coordinated cutover.

## 1. Decision

TomeWisp is renamed to **OpenAllay**. The product has no Chinese brand name;
the English name is used consistently in the mod, UI, documentation, release
artifacts, community pages, and developer tooling.

The spelling and casing are fixed:

- Public name: `OpenAllay`
- Minecraft namespace and Mod ID: `openallay`
- Java root package: `dev.openallay`
- Environment-variable prefix: `OPENALLAY_`

`TomeWisp`, `书灵`, and `OpenAlley` are not current product names. `TomeWisp`
may remain only in Git history or in a clearly marked historical rename note.

## 2. Rationale

The former name suggests a book or documentation companion. It does not
communicate the broader product: a Minecraft-native Agent with open model
selection, player-created Skills, trusted extensions, grounded knowledge,
server-hosted models, and rich in-game UI.

`OpenAllay` gives the product a short, English-only identity. `Open` conveys
the open ecosystem and provider-neutral runtime. `Allay` supplies a
Minecraft-native helper metaphor without binding the product to recipes, one
model provider, or one implementation technique.

`Allay` is also an official Minecraft creature name. Before formal
distribution, the project must complete a platform, domain, and trademark
review and use original project artwork. The project must not imply an
official Mojang or Microsoft affiliation.

## 3. Product naming architecture

The public product remains a single brand. These names are descriptive
sub-products, not independent brands:

- `OpenAllay`: the Minecraft Agent Mod and platform;
- `OpenAllay Skills`: player-created and community-distributed Skills;
- `OpenAllay Extensions`: trusted plugin and integration ecosystem;
- `OpenAllay Host`: server-side model, concurrency, quota, and routing service;
- `OpenAllay Studio`: rich UI, visual content, and future Ponder-related work.

The sub-product names are documentation conventions only until separately
approved. This rename does not create new modules or services.

## 4. Scope of the rename

This is a product-identity and identifier change. Agent behavior, tool
semantics, authority rules, context policy, persistence model, and loader
architecture remain unchanged.

The affected surfaces are:

1. **Build and metadata**: Gradle coordinates, root project name, Mod ID,
   Fabric metadata, NeoForge metadata, artifact names, CI artifact labels, and
   repository URLs.
2. **Java namespaces**: the `dev.tomewisp` package tree in common, Fabric,
   NeoForge, and tests; classes whose names begin with `TomeWisp`.
3. **Minecraft namespaces**: tool IDs, capability IDs, payload IDs, evidence
   provenance IDs, semantic keys, trace resource IDs, and development command
   names.
4. **Resources**: `assets/tomewisp/`, `data/tomewisp/`, language keys, bundled
   Skill paths, and deterministic Agent traces.
5. **Runtime storage**: `config/tomewisp/`, client and server model files,
   display and recipe settings, capabilities, and `history.sqlite3`.
6. **Developer interfaces**: `TOMEWISP_*` environment variables, E2E system
   properties, live-provider scripts, fixture identifiers, and verification
   scripts.
7. **Documentation and evidence**: README, AGENTS instructions, development
   documentation, current ISME decisions, active specifications, verification
   reports, logs, and screenshots that present the current product name.

The repository inventory found the old identifier in the common source and
test trees, both loader trees, resource namespaces, scripts, CI metadata, and
current documentation. This confirms that the rename must be coordinated; a
README-only change is insufficient.

## 5. Identifier mapping

| Existing identity | OpenAllay identity | Policy |
| --- | --- | --- |
| `TomeWisp` | `OpenAllay` | Replace in current product-facing text and type names |
| `tomewisp` | `openallay` | Replace in Mod IDs, paths, namespaces, and artifact names |
| `dev.tomewisp` | `dev.openallay` | Rename the complete Java package tree |
| `TOMEWISP_*` | `OPENALLAY_*` | Rename developer environment variables |
| `config/tomewisp` | `config/openallay` | Use a new clean runtime directory |
| `tomewisp:*` | `openallay:*` | Rename internal tool, capability, bridge, and evidence IDs |
| `/tomewisp` | `/openallay` | Rename the development command; no public alias is required |
| `assets/tomewisp` | `assets/openallay` | Rename the resource namespace |
| `data/tomewisp` | `data/openallay` | Rename the data namespace |

The bridge payload schema and protocol version remain unchanged where their
wire structure is unchanged. The namespace change intentionally makes old
pre-rename client/server builds incompatible, which prevents ambiguous mixed
identity during one-to-one testing.

## 6. Persistence and compatibility policy

The project is still pre-release and the existing runtime state is test data.
There is no production history, financial state, published world data, or
formal compatibility contract to preserve. Therefore:

- do not add a `tomewisp`-to-`openallay` runtime migration;
- do not read old configuration files as a fallback;
- do not preserve old packet IDs or tool namespaces as aliases;
- do not automatically delete an existing `config/tomewisp` directory;
- start one-to-one testing with a clean `config/openallay` directory;
- treat old traces and reports as historical fixtures and regenerate active
  fixtures under the new namespace.

This policy is intentionally destructive with respect to local test
configuration, but it avoids a permanent compatibility layer before the first
formal release.

## 7. Cutover procedure

The implementation must be performed as one coordinated change set:

1. Freeze new feature work and record the pre-rename build and test baseline.
2. Rename Gradle, Mod metadata, Java packages, class names, and loader service
   descriptors.
3. Rename common protocol/resource identifiers and update both loaders in
   parity.
4. Move resource and data namespaces and update all language, Skill, and trace
   references.
5. Move runtime paths and rename developer environment variables and E2E
   properties.
6. Update tests, fixtures, scripts, reports, logs, screenshots, README,
   AGENTS instructions, and current design/decision documentation.
7. Run the complete verification gate and perform an old-identifier scan.
8. Distribute one OpenAllay build to the one-to-one testers and record the
   cutover build/version in the test notes.

The change must not mix unrelated feature work or behavior refactors.

## 8. Verification requirements

The rename is complete only when all of the following are true:

- common tests pass;
- Fabric and NeoForge builds pass;
- both loader metadata files identify `openallay`;
- common and loader package isolation tests pass;
- resource and data fixtures load from `openallay` namespaces;
- E2E, live-provider, and packaging scripts use `OPENALLAY_*` names;
- client and server model configuration use `config/openallay`;
- no current runtime code contains `tomewisp` or `TomeWisp` identifiers;
- no current player-facing UI says TomeWisp or 书灵;
- old and new builds cannot be accidentally mixed during one-to-one testing;
- any remaining `TomeWisp` text is explicitly historical.

The full production gate remains:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
```

## 9. Out of scope

This document does not authorize or describe changes to:

- Agent loop behavior;
- context compaction or history semantics;
- model-provider adapters;
- recipe or knowledge authority;
- Skill permissions;
- dynamic UI capabilities;
- server concurrency or quota policy;
- formal distribution or marketplace launch.

Those features retain their existing decisions and tests.

