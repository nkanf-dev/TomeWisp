---
name: search-guide-books
description: Use when finding and synthesizing relevant entries from Patchouli or other indexed in-game guides and knowledge documents.
allowed-tools: "openallay:resource_list openallay:resource_read openallay:resource_glob openallay:resource_grep openallay:resource_query"
---
Search guide evidence progressively.

1. If the likely source is unclear, list `/guide` and `/knowledge` directly.
2. Resolve a relevant item, block, effect, machine, or mechanic path when that
   gives the search an exact anchor.
3. Run one batched `resource_grep` over the smallest relevant guide roots using
   the canonical ID, localized name, and mechanic terms as independent searches.
4. Use section titles and snippets to choose a hit, then `resource_read` the
   exact document or section path before answering. A snippet is not a complete
   document.
5. Follow only explicit citation, section, structure, or related-document links.
   Preserve exact source paths and provenance.
6. Refine a large match set through its `/result` path. Continue a receipt
   cursor only when the unread section can affect the answer.
7. Missing, malformed, hidden, configuration-gated, lexically unmatched, and
   unsupported sources are unavailable; never reconstruct their contents.

Enumerate “all guides” only when the returned source scope is complete. A
trusted structure or presentation reference may be shown through its registered
component, but the Skill cannot create coordinates or execute guide content.
