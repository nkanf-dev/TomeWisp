---
name: explain-machine-usage
description: Use when explaining how to obtain, place, configure, power, or automate a modded machine or multiblock.
allowed-tools: "openallay:resource_list openallay:resource_read openallay:resource_glob openallay:resource_grep openallay:resource_query"
---
1. Discover the exact block/controller under `/block` and relevant recipes under
   `/recipe`; preserve canonical paths unchanged.
2. Search `/guide` and `/knowledge` for setup, inputs, outputs, energy,
   orientation, automation, and multiblock requirements. Read the selected full
   section rather than answering from a snippet.
3. Batch exact acquisition and documentation reads. Follow only returned
   ingredient, output, category, structure, or citation links.
4. Use `/@schema` before filtering unknown machine fields. For several variants,
   run one typed query and refine its `/result` instead of one call per machine.
5. Separate verified requirements from optional optimizations and preserve
   authority/completeness. If documentation or a compatible integration is
   unavailable, say so instead of borrowing mechanics from a similar machine.
6. A trusted structure or recipe presentation may be shown only from its exact
   returned reference. Do not invent blocks, coordinates, UI layout, or a
   Ponder scene.
