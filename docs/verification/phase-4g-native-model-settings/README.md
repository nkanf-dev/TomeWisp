# Phase 4G Native Model Settings Verification

Verification date: 2026-07-18 (Asia/Shanghai)

Scope: atomic model-profile administration, isolated redacted connection
probing, generation-safe metadata reconciliation, native Models UI, and
Fabric/NeoForge lifecycle parity.

## Deterministic clean gate

Command:

```bash
./gradlew clean :common:test :fabric:build :neoforge:build
```

Result: `BUILD SUCCESSFUL` in 41 seconds.

- common tests: 296
- failures: 0
- errors: 0
- skipped: 2 opt-in live-provider tests
- Fabric build: successful
- NeoForge build: successful
- common-to-loader source boundary: passed by `ClientArchitectureTest`
- settings key parity for `en_us` and `zh_cn`: passed

The build emitted the repository's existing Javadoc record-component warnings
and NeoForge's existing missing Fabric `EnvType.CLIENT` annotation warnings;
neither is a compilation or test failure.

## Production artifacts

```text
cc04e580d8d04404bbd92dcded947260b964380a8fd4c2dcf98e75a38957b922  fabric/build/libs/tomewisp-fabric-26.2-0.1.0-SNAPSHOT.jar
614da7cb7b1c156560cf22d385ff6c4945d6968e73ba0e128a480f5de8c84091  neoforge/build/libs/tomewisp-neoforge-26.2-0.1.0-SNAPSHOT.jar
```

Both production JARs were scanned for the known deterministic test secrets,
probe output sentinels, provider-body sentinels, and generic long `sk-` token
forms. Both scans were clean. Source and Javadoc JARs were excluded because the
claim concerns production runtime artifacts.

## Syntax and repository checks

- `bash -n scripts/*.sh`: passed
- `python3 -m py_compile` for every tracked `scripts/*.py`: passed
- `jq empty` for every tracked JSON file: passed
- `git diff --check`: passed

## Live and graphical truth

The verification process did not contain `TOMEWISP_API_KEY`, so the authorized
real connection probe was not run. No credential from conversation text was
copied into a command, file, log, fixture, screenshot, or report. The opt-in
`scripts/live-model-smoke.sh settings-probe` path is compiled and syntax-checked
and reads the credential only from the environment variable named by an ignored
strict model-profile JSON file.

No graphical Minecraft client was launched for this package. Therefore this
report claims deterministic UI projection/layout coverage and both-loader
build parity, not manual screen appearance or real-client input acceptance.

The credential exposed in conversation should be rotated after any later live
probe, independently of this deterministic result.
