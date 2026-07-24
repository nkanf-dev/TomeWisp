# Rhino Agent runtime live-provider acceptance

- Date: 2026-07-24
- Model: `deepseek-v4-flash`
- Protocol: OpenAI-compatible Chat Completions
- Credential source: interactive environment input; never written to a file or
  report
- Mode: non-streaming provider response, production Agent loop and Tool
  continuation
- Test: `LiveJavascriptAgentAcceptanceTest`
- Result: passed across two targeted scenario runs after one combined-run
  efficiency failure exposed and corrected a redundant verification path

## Highest-damage sword

The model loaded `analyze-game-data`, loaded
`references/examples.md`, and used JavaScript over the complete captured item
array. It returned:

```text
example:obsidian_sword
damage: 14
```

The final direct-host run used one set-level JavaScript program and no per-item
Tool loop. It read the lazy Java-backed `mc.items` view, returned a complete
two-row ranking, and answered from that result.

## Minimum-material container

The model loaded `analyze-game-data`, loaded
`references/examples.md`, filtered all container outputs, summed consumed
ingredient units, and returned:

```text
recipe: example:small_pouch
output: example:small_pouch
material units: 2
```

The final direct-host run used one ranking program. It read `mc.items` and
`mc.recipes`, returned the complete two-row ranking, and stopped without
craftability or detail-verification calls.

## Runtime and context safeguards exercised

The accepted build uses KubeJS-Mods Rhino in a fresh denied-host context.
`MinecraftAgentHostGraph` selected only the declared roots and
`RhinoHostAdapter` exposed the original detached Java records and collections
through lazy read-only views. Request input and workspace values were not
serialized into source or parsed with `JSON.parse`; only each explicit script
return crossed the canonical JSON output boundary. Both scenarios exercised
the production prompt, bundled Skill/reference, model codec, Agent
continuation, Tool registry, direct host adapter, result normalizer, bounded
model projection, and evidence summary.

## Provider observation

The first direct-host combined run produced both correct answers, but the
container scenario made three JavaScript calls after redundantly attempting
craftability and inspecting an already complete result. That run correctly
failed the two-call efficiency gate. The Tool description, system termination
rule, and matching Skill example were tightened; subsequent targeted runs used
one JavaScript call for each scenario.

Two intervening attempts received provider HTTP 503 before the affected
scenario could execute. Those failures are retained as endpoint availability
observations, not reported as Rhino or Tool failures. Non-streaming mode remains
the accepted provider configuration because earlier streaming attempts ended
with `model_transport_error`.
