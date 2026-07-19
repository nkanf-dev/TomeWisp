# OpenAllay 0.1.1-SNAPSHOT emergency verification

Date: 2026-07-20

## Scope

This emergency verification covers the bounded model-facing Tool projection,
progressive result reads, request-lifetime cleanup, provider codecs, and both
production loader artifacts. Internal normalized JSON remains the source used
for validation and player-facing projections; provider Tool messages receive
only the bounded text projection.

It also covers cross-provider model-metadata alias caching and opening an
issued MC百科 search reference as sanitized article text through the normal
knowledge Tool boundary.

## Local gate

The following focused gate passed:

```text
./gradlew :common:test \
  --tests '*ProgressiveToolResultExecutorTest' \
  --tests '*GameGuideAgentTest' \
  --tests '*OpenAiChatClientTest' \
  --tests '*AnthropicMessagesClientTest' \
  :fabric:build :neoforge:build
```

Additional focused tests for context compaction, recipe queries, metadata
refresh/cache, online knowledge, UI Tool details, prompt assembly, localization,
and settings passed before the final loader gate.

## Retained artifacts

```text
fef1827a27c64a4b24b7d562e5477d46754473dd60f2d3e45e787e5a81ee9330  openallay-fabric-26.2-0.1.1-SNAPSHOT.jar
81e8e367ee2f22780e3d0a904aa7cd4b31401d51fc23ef38ccd07a795a7771c7  openallay-neoforge-26.2-0.1.1-SNAPSHOT.jar
```

## Deliberately not run locally

No graphical-client matrix or billable live-provider long-task suite was run
for this emergency patch. The tag-triggered release workflow remains responsible
for the clean full common test suite, both loader builds, distribution/package
verification, GitHub release, and Modrinth publication.
