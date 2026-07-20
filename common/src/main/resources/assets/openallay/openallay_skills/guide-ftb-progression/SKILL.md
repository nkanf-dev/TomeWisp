---
name: guide-ftb-progression
description: Use when guiding a player through currently visible FTB Quests progression, prerequisites, branches, or rewards.
metadata:
  openallay/required-mods: "ftbquests"
allowed-tools: "openallay:resource_list openallay:resource_read openallay:resource_glob openallay:resource_grep openallay:resource_query"
---
Use only visible, actor-authorized quest resources under `/guide` or
`/knowledge`.

1. Search for the requested goal, then read the exact quest document and its
   explicit prerequisite/reward links.
2. Use one `follow` query to traverse a returned prerequisite relation; keep
   depth bounded and preserve branches rather than inventing a linear order.
3. Refine large quest sets through `/result`, and continue a cursor only when
   omitted visible quests affect the requested path.
4. Explain prerequisites before rewards and distinguish required from optional
   branches. Never reveal hidden text or infer hidden dependencies.
5. If the FTB Quests source is unavailable for this version or configuration,
   report that and fall back only to other visible guide evidence.
