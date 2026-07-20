# Design and plan history

OpenAllay adopted its current name on 2026-07-19. Specifications and plans
written before that cutover are retained as engineering history and may use
the former product name. They do not define a current runtime identifier,
compatibility alias, storage fallback, or public brand.

Current product identity is defined by
[`specs/2026-07-19-openallay-rename-design.md`](specs/2026-07-19-openallay-rename-design.md).

The emergency `0.1.1-SNAPSHOT` bounded Tool-result specification, plan, and
SKMB-025 decision were removed by rollback commit `659d698`. They are withdrawn
and must not be restored from Git history, cited as authority, or used as a
compatibility target. In particular, raw/dotted JSON projection and a special
`read_tool_result` escape hatch are not interim Resource VFS behavior. Their
incident evidence is carried forward by
[`specs/2026-07-20-resource-vfs-tool-architecture-design.md`](specs/2026-07-20-resource-vfs-tool-architecture-design.md)
and SKMB-2026-07-20-026. Its executable migration sequence is
[`plans/2026-07-20-resource-vfs-tool-architecture.md`](plans/2026-07-20-resource-vfs-tool-architecture.md).

For current work, read the accepted decision first, then the Resource VFS
design and plan. Earlier Phase 3/4 domain-Tool plans remain useful historical
evidence for recipe, guide, history, bridge and UI behavior, but SKMB-026 owns
their Agent-facing retrieval and context-projection architecture.
