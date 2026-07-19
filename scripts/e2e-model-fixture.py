#!/usr/bin/env python3
"""Deterministic loopback-only OpenAI-compatible fixture for real-client E2E."""

import argparse
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


STEP_NAMES = (
    "openallay__search_recipes",
    "openallay__get_recipe",
    "openallay__inspect_inventory",
    "openallay__calculate_craftability",
    "openallay__list_knowledge_sources",
)

GAME_STATE_STEPS = (
    ("openallay__inspect_game_state", {"section": "OVERVIEW", "query": "summary"}),
    ("openallay__inspect_game_state", {"section": "MODS", "query": "list"}),
    ("openallay__inspect_game_state", {"section": "OPTIONS", "query": "groups"}),
    ("openallay__inspect_game_state", {"section": "PACKS", "query": "summary"}),
    ("openallay__inspect_game_state", {"section": "SHADERS", "query": "summary"}),
    ("openallay__inspect_game_state", {"section": "DIAGNOSTICS", "query": "categories"}),
    ("openallay__inspect_game_state", {"section": "PLAYER", "query": "summary"}),
    ("openallay__inspect_game_state", {"section": "WORLD_QUERY", "query": "time"}),
)

RECIPE_OUTPUT = os.environ.get(
    "OPENALLAY_E2E_RECIPE_OUTPUT", "minecraft:iron_block")
RECIPE_ID = os.environ.get(
    "OPENALLAY_E2E_RECIPE_ID", "minecraft:iron_block")
RECIPE_LABEL = os.environ.get("OPENALLAY_E2E_RECIPE_LABEL", "铁块")
RECIPE_SOURCE = os.environ.get("OPENALLAY_E2E_RECIPE_SOURCE")


def recipe_reference(request, output_item=None, recipe_id=None, recipe_type=None):
    matching_search = None
    observed_tools = []
    for message in reversed(request.get("messages", [])):
        if message.get("role") != "tool":
            continue
        try:
            normalized = json.loads(message.get("content", ""))
            observed_tools.append({
                "status": normalized.get("status") if isinstance(normalized, dict) else None,
                "code": normalized.get("code") if isinstance(normalized, dict) else None,
                "outputType": normalized.get("outputType") if isinstance(normalized, dict) else None,
                "valueKeys": sorted(normalized.get("value", {}).keys())
                if isinstance(normalized, dict)
                and isinstance(normalized.get("value"), dict) else [],
            })
            if output_item is not None:
                query = normalized["value"]["query"]
                if query.get("outputItem") != output_item:
                    continue
            matching_search = normalized
            recipes = normalized["value"]["recipes"]
            for recipe in recipes:
                if recipe_id is not None and recipe.get("id") != recipe_id:
                    continue
                if recipe_type is not None and recipe.get("type") != recipe_type:
                    continue
                references = recipe.get("references", [recipe["reference"]])
                reference = next((value for value in references
                                  if RECIPE_SOURCE is None
                                  or value.get("sourceId") == RECIPE_SOURCE), None)
                if reference is not None:
                    return {key: reference[key]
                            for key in ("sourceId", "generation", "recipeId")}
        except (KeyError, IndexError, TypeError, json.JSONDecodeError):
            continue
    if matching_search is not None:
        providers = matching_search.get("value", {}).get("catalog", {}).get("providers", [])
        summary = [{
            "sourceId": provider.get("sourceId"),
            "state": provider.get("state"),
            "recipeCount": provider.get("recipeCount"),
            "diagnostics": [diagnostic.get("code")
                            for diagnostic in provider.get("diagnostics", [])[:3]],
        } for provider in providers]
        candidates = [{"id": recipe.get("id"), "type": recipe.get("type")}
                      for recipe in matching_search.get("value", {}).get("recipes", [])]
        raise ValueError("search returned no matching recipe reference; candidates="
                         + json.dumps(candidates, separators=(",", ":"))
                         + "; providers="
                         + json.dumps(summary, separators=(",", ":")))
    raise ValueError("search result did not contain a recipe reference; observed="
                     + json.dumps(observed_tools, separators=(",", ":")))


def normalized_tool_values(request):
    for message in request.get("messages", []):
        if message.get("role") != "tool":
            continue
        try:
            normalized = json.loads(message.get("content", ""))
        except (TypeError, json.JSONDecodeError):
            continue
        if (isinstance(normalized, dict)
                and normalized.get("status") == "success"
                and isinstance(normalized.get("value"), dict)):
            yield normalized["value"]


def ingredient_check(request):
    recipe = None
    counts = {}
    for value in normalized_tool_values(request):
        candidate = value.get("recipe")
        if isinstance(candidate, dict) and candidate.get("id") == RECIPE_ID:
            recipe = candidate
        candidate_counts = value.get("counts")
        if isinstance(candidate_counts, dict):
            counts = candidate_counts
    if recipe is None:
        raise ValueError("exact recipe result did not contain recipe details")

    ingredients = []
    for requirement in recipe.get("ingredients", []):
        if not isinstance(requirement, dict):
            continue
        item_id = None
        for alternative in requirement.get("alternatives", []):
            if not isinstance(alternative, dict):
                continue
            resolved = alternative.get("resolvedItems", [])
            if isinstance(resolved, list) and resolved:
                item_id = resolved[0]
                break
            if alternative.get("kind") == "item":
                item_id = alternative.get("id")
                break
        if not isinstance(item_id, str):
            continue
        ingredients.append({
            "itemId": item_id,
            "required": requirement.get("count", 0),
            "available": counts.get(item_id, 0),
            "label": item_id.split(":", 1)[-1].replace("_", " "),
        })
    if not ingredients:
        raise ValueError("exact recipe result did not expose grounded item ingredients")
    return ingredients


def step(request, completed):
    if completed == 0:
        return STEP_NAMES[completed], {"outputItem": RECIPE_OUTPUT}
    if completed == 1:
        return STEP_NAMES[completed], recipe_reference(
            request, RECIPE_OUTPUT, RECIPE_ID)
    if completed == 2:
        return STEP_NAMES[completed], {}
    if completed == 3:
        reference = recipe_reference(
            request, RECIPE_OUTPUT, RECIPE_ID)
        reference["crafts"] = 1
        return STEP_NAMES[completed], reference
    return STEP_NAMES[completed], {}


def assistant_content(request, completed):
    if completed == 0:
        return ("# Phase 4 图形验收\n\n我会先查询 **"
                + RECIPE_LABEL + "配方**，再核对库存和知识来源。")
    if completed == 1:
        return """
已获得搜索证据，现在读取精确配方。

```openallay-component
{"schemaVersion":1,"type":"status_badge","properties":{"state":"INFO","label":"配方搜索完成"},"fallback":"配方搜索完成","narration":"配方搜索完成"}
```
""".strip()
    if completed == 2:
        reference = recipe_reference(
            request, RECIPE_OUTPUT, RECIPE_ID)
        component = {
            "schemaVersion": 1,
            "type": "recipe_grid",
            "properties": {
                **reference,
                "label": RECIPE_LABEL + "配方",
            },
            "fallback": RECIPE_LABEL + "配方已确认",
            "narration": RECIPE_LABEL + "配方已确认，可打开配方查看器",
        }
        item_row = {
            "schemaVersion": 1,
            "type": "item_row",
            "properties": {
                "items": [{"itemId": RECIPE_OUTPUT, "count": 1,
                           "label": RECIPE_LABEL}],
            },
            "fallback": "配方产出 1 个" + RECIPE_LABEL,
            "narration": "配方产出：一个" + RECIPE_LABEL,
        }
        return "## 精确配方\n\n" + "```openallay-component\n" \
            + json.dumps(component, ensure_ascii=False, separators=(",", ":")) \
            + "\n```\n\n```openallay-component\n" \
            + json.dumps(item_row, ensure_ascii=False, separators=(",", ":")) \
            + "\n```\n\n下一步检查玩家库存。"
    if completed == 3:
        component = {
            "schemaVersion": 1,
            "type": "ingredient_check",
            "properties": {"ingredients": ingredient_check(request)},
            "fallback": "已根据精确配方与库存快照核对材料",
            "narration": "材料检查完成",
        }
        return """
- 库存快照
  - 已脱离 Minecraft 对象并安全捕获
  - 只包含玩家自己的背包
- 下一步：计算一次制作

```openallay-component
INGREDIENT_CHECK
```

```openallay-component
{"schemaVersion":1,"type":"world_mutation","properties":{"command":"/give"},"fallback":"不支持的组件已安全降级为文本","narration":"不支持的组件"}
```
""".replace("INGREDIENT_CHECK", json.dumps(
            component, ensure_ascii=False, separators=(",", ":"))).strip()
    if completed == 4:
        reference = recipe_reference(
            request, RECIPE_OUTPUT, RECIPE_ID)
        craftability = {
            "schemaVersion": 1,
            "type": "craftability_summary",
            "properties": {
                **reference,
                "craftable": False,
                "conclusive": True,
                "requestedCrafts": 1,
                "maximumCrafts": 0,
            },
            "fallback": "当前材料不足，无法制作" + RECIPE_LABEL,
            "narration": "制作检查完成：材料不足",
        }
        return """
> 可制作性由 Java 确定性计算，不交给模型猜测。

```openallay-component
{"schemaVersion":1,"type":"progress_steps","properties":{"steps":[{"id":"recipe","label":"配方证据","state":"COMPLETE"},{"id":"inventory","label":"库存证据","state":"COMPLETE"},{"id":"knowledge","label":"知识来源","state":"ACTIVE"}]},"fallback":"配方和库存已检查，正在读取知识来源","narration":"验收进度：配方和库存完成，知识来源进行中"}
```
""".strip() + "\n\n```openallay-component\n" \
            + json.dumps(craftability, ensure_ascii=False, separators=(",", ":")) \
            + "\n```"
    sources = knowledge_sources(request)
    source_component = {
        "schemaVersion": 1,
        "type": "source_summary",
        "properties": {
            "sources": [{"sourceId": source_id, "label": source_label(source_id)}
                        for source_id in sources],
        },
        "fallback": "已列出当前可用知识来源",
        "narration": "当前可用知识来源已列出",
    }
    return """
## 完成

这是 **粗体重点**、*斜体说明* 与 `minecraft:iron_block` 行内代码的真实 Markdown 验收。

1. 先解析准确资源
2. 再读取精确配方
3. 最后核对玩家自己的库存

```text
all facts <- validated tool evidence
no writes <- read-only tools only
```

| 检查项 | 结果 |
|---|---|
| 配方 | 已找到并精确读取 |
| 库存/制作 | 已由工具计算 |
| 知识来源 | 已列出 |

可继续查看 [[tw:item|minecraft:iron_block|铁块]]的配方或用途。

[外部链接](https://example.invalid) 与 ![外部图片](https://example.invalid/image.png)
不会变成可执行控件；<button onclick="danger()">HTML</button> 也只会安全降级。

```openallay-component
{"schemaVersion":1,"type":"choice_group","properties":{"prompt":"下一步想查看什么？","choices":[{"id":"recipe","label":"配方详情"},{"id":"usage","label":"物品用途"}]},"fallback":"可选择配方详情或物品用途","narration":"显示两个安全选项"}
```
""".strip() + "\n\n```openallay-component\n" \
        + json.dumps(source_component, ensure_ascii=False, separators=(",", ":")) \
        + "\n```"


def knowledge_sources(request):
    for message in reversed(request.get("messages", [])):
        if message.get("role") != "tool":
            continue
        try:
            normalized = json.loads(message.get("content", ""))
            value = normalized.get("value", {})
            sources = value.get("sources")
            if not isinstance(sources, list):
                continue
            ids = [source.get("id") for source in sources
                   if isinstance(source, dict) and isinstance(source.get("id"), str)]
            if ids:
                return ids
            evidence = value.get("evidence", [])
            ids = [entry.get("sourceId") for entry in evidence
                   if isinstance(entry, dict)
                   and isinstance(entry.get("sourceId"), str)]
            if ids:
                return list(dict.fromkeys(ids))
        except (TypeError, json.JSONDecodeError):
            continue
    raise ValueError("knowledge-source result did not contain a stable source reference")


def source_label(source_id):
    return {
        "patchouli:resources": "资源指南",
        "viewer:jei": "JEI 配方来源",
        "viewer:rei": "REI 配方来源",
    }.get(source_id, "游戏内知识来源")


def game_state_assistant_content(completed, world_query_permission_denied=False):
    labels = (
        "运行概览", "已安装模组", "设置分组", "资源包与数据包",
        "光影状态", "F3 诊断类别", "玩家可见状态", "只读世界时间",
    )
    if completed < len(labels):
        return "## 游戏外层状态验收\n\n正在读取：**" + labels[completed] + "**。"
    world_query_line = (
        "- 只读世界查询明确返回权限不足；Agent 保留该结构化失败并继续完成"
        if world_query_permission_denied
        else "- 已读取只读世界查询"
    )
    return f"""
## 游戏外层状态验收完成

- 已读取运行环境和安装模组
- 已读取设置、资源包、数据包与光影集成状态
- 已读取 F3 类诊断和玩家可见状态
{world_query_line}

所有结果来自同一请求开始时脱离 Minecraft 对象的只读快照；不可用或不完整部分保持明确标注。
""".strip()


def validated_game_state_results(turn_messages, allow_world_query_permission_failure=False):
    expected = [arguments["section"] for _, arguments in GAME_STATE_STEPS]
    observed = []
    world_query_permission_denied = False
    for message in turn_messages:
        if message.get("role") != "tool":
            continue
        try:
            result = json.loads(message.get("content", ""))
            if result.get("status") != "success":
                if (allow_world_query_permission_failure
                        and len(observed) == len(expected) - 1
                        and result.get("code") == "permission_denied"):
                    observed.append(expected[-1])
                    world_query_permission_denied = True
                    continue
                raise ValueError("game-state tool returned failure")
            section = result["value"]["section"]
        except (KeyError, TypeError, json.JSONDecodeError) as failure:
            raise ValueError("game-state tool result is malformed") from failure
        if len(observed) >= len(expected) or section != expected[len(observed)]:
            raise ValueError("game-state tool result section is out of order")
        observed.append(section)
    return observed, world_query_permission_denied


def content_events(content):
    # Fixed small chunks deliberately split Markdown and component tokens.
    chunks = [content[index:index + 17] for index in range(0, len(content), 17)]
    return [{"content": chunk} for chunk in chunks]


def current_user_turn(request):
    messages = request.get("messages", [])
    latest_user = -1
    user_text = ""
    for index, message in enumerate(messages):
        if message.get("role") == "user":
            latest_user = index
            user_text = message.get("content", "")
    return user_text, messages[latest_user + 1:]


class Handler(BaseHTTPRequestHandler):
    server_version = "OpenAllayFixture/1"

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        length = int(self.headers.get("content-length", "0"))
        request = json.loads(self.rfile.read(length))
        user_text, turn_messages = current_user_turn(request)
        completed = sum(1 for message in turn_messages
                        if message.get("role") == "tool")
        history_seed = user_text.startswith("OpenAllay E2E 历史分页种子 ")
        server_client_tools = user_text.startswith(
            "OpenAllay E2E 服务端模型反向工具验收")
        game_state = (user_text.startswith("OpenAllay E2E 游戏外层状态验收")
                      or server_client_tools)
        world_query_permission_denied = False
        if game_state:
            try:
                observed, world_query_permission_denied = validated_game_state_results(
                    turn_messages, True)
                completed = len(observed)
            except ValueError as failure:
                self.send_error(422, str(failure))
                return
        try:
            content = (
                "历史分页种子已记录。" if history_seed
                else "服务端模型已完成客户端状态读取；无权限的只读世界查询作为工具失败返回后，Agent 仍正常完成。"
                if server_client_tools and completed == len(GAME_STATE_STEPS)
                else game_state_assistant_content(
                    completed, world_query_permission_denied) if game_state
                else assistant_content(request, completed))
        except ValueError as failure:
            self.send_error(422, str(failure))
            return
        deltas = content_events(content)
        steps = GAME_STATE_STEPS if game_state else STEP_NAMES
        if not history_seed and completed < len(steps):
            try:
                name, arguments = (steps[completed] if game_state
                                   else step(request, completed))
            except ValueError as failure:
                self.send_error(422, str(failure))
                return
            available = {tool["function"]["name"]
                         for tool in request.get("tools", [])}
            if name not in available:
                self.send_error(422, "required E2E tool unavailable: " + name)
                return
            deltas.append({"tool_calls": [{
                "index": 0,
                "id": "fixture-" + str(completed + 1),
                "type": "function",
                "function": {"name": name, "arguments": json.dumps(arguments)},
            }]})
            reason = "tool_calls"
        else:
            reason = "stop"
        events = []
        for index, delta in enumerate(deltas):
            event = {
                "id": "openallay-fixture",
                "model": "openallay-e2e-fixture",
                "choices": [{
                    "index": 0,
                    "delta": delta,
                    "finish_reason": reason if index == len(deltas) - 1 else None,
                }],
            }
            if index == len(deltas) - 1:
                event["usage"] = {"prompt_tokens": 10, "completion_tokens": 4}
            events.append("data: " + json.dumps(
                event, ensure_ascii=False, separators=(",", ":")) + "\n\n")
        body = ("".join(events) + "data: [DONE]\n\n").encode()
        # Keep one real-client render window open long enough for the native progress strip
        # to be captured. This is loopback-only deterministic fixture latency.
        if not history_seed:
            time.sleep(0.35)
        self.send_response(200)
        self.send_header("content-type", "text/event-stream")
        self.send_header("cache-control", "no-store")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, pattern, *args):
        print("fixture:", pattern % args, flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"OpenAllay E2E model fixture listening on 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
