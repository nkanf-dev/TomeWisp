# Session Actions And Safe Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add double-confirmed session deletion, complete managed current-session export, and visible chat copying to the native Guide screen.

**Architecture:** GuideService captures an immutable sequence-aware export snapshot and a narrow collector reads durable pages without mutating viewport state. A client-only atomic writer owns the fixed export directory, while TomeWispScreen maps native confirmation, clipboard, and asynchronous notices onto those services.

**Tech Stack:** Java 25 records and CompletableFuture, existing GuideHistoryAccess paging, Minecraft ConfirmScreen and KeyboardHandler, UTF-8 NIO atomic files, JUnit 5

---

### Task 1: Sequence-safe export capture

**Files:**
- Create: `common/src/main/java/dev/tomewisp/guide/export/GuideSessionExportSnapshot.java`
- Create: `common/src/main/java/dev/tomewisp/guide/export/GuideSessionExportCollector.java`
- Modify: `common/src/main/java/dev/tomewisp/guide/GuideService.java`
- Test: `common/src/test/java/dev/tomewisp/guide/export/GuideSessionExportCollectorTest.java`

- [x] Write failing multi-page, live-overlay, cursor-loop, and history-failure tests.
- [x] Implement immutable sequence-aware capture and iterative asynchronous paging.
- [x] Expose `GuideService.captureSelectedSessionForExport()` without changing the visible history window.
- [x] Run the focused collector and GuideService history tests.

### Task 2: Managed atomic plain-text export

**Files:**
- Create: `common/src/main/java/dev/tomewisp/client/gui/export/GuideSessionExporter.java`
- Test: `common/src/test/java/dev/tomewisp/client/gui/export/GuideSessionExporterTest.java`

- [x] Write failing chronology, redaction, confinement, symlink, and no-partial-file tests.
- [x] Implement the closed plain-text formatter and defense-in-depth redactor.
- [x] Implement fixed-root temporary-write plus atomic-move publication.
- [x] Run the focused exporter tests.

### Task 3: Native confirmations, export, and copy actions

**Files:**
- Modify: `common/src/main/java/dev/tomewisp/client/gui/TomeWispScreen.java`
- Modify: `common/src/main/resources/assets/tomewisp/lang/en_us.json`
- Modify: `common/src/main/resources/assets/tomewisp/lang/zh_cn.json`
- Modify: `common/src/test/java/dev/tomewisp/client/gui/TomeWispScreenProjectionTest.java`

- [x] Add pure tests for copyable row text and captured delete-confirmation target/text.
- [x] Replace direct close with two Minecraft `ConfirmScreen` stages.
- [x] Add asynchronous managed export and player-facing completion/failure notices.
- [x] Add user/assistant row copy actions using Minecraft's clipboard handler.
- [x] Run GUI projection tests and compile common code.

### Task 4: Decision and full focused verification

**Files:**
- Modify: `docs/isme/SKMB.md`
- Create: `docs/isme/decisions/2026-07-19-023-session-actions-and-safe-export.md`
- Modify: `docs/development.md`

- [x] Index accepted transitions, invariants, and failures in SKMB.
- [x] Document managed export location, redaction, and native interaction behavior.
- [x] Run all new tests, adjacent history/UI tests, and `git diff --check`.
- [x] Leave changes uncommitted for the root agent's unified Phase 4 commit.
