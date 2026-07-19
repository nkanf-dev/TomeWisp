#!/usr/bin/env bash
set -euo pipefail

repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repository"

fail() {
  printf 'Modrinth publication failed: %s\n' "$1" >&2
  exit 1
}

if (( $# != 1 )); then
  fail 'usage: publish-modrinth.sh <v-prefixed-version>'
fi

tag=$1
version=${tag#v}
[[ "$tag" == v* && -n "$version" ]] || fail 'version must have a v prefix'
[[ -n "${MODRINTH_TOKEN:-}" ]] || fail 'MODRINTH_TOKEN is required'

configured_version=$(sed -n 's/^version=//p' gradle.properties)
minecraft_version=$(sed -n 's/^minecraft_version=//p' gradle.properties)
[[ "$configured_version" == "$version" ]] \
  || fail "tag version $version does not match Gradle version $configured_version"

api=https://api.modrinth.com/v2
slug=openallay
user_agent="nkanf-dev/OpenAllay/${version} (https://github.com/nkanf-dev/OpenAllay)"
work=$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/openallay-modrinth.XXXXXX")
trap 'rm -rf "$work"' EXIT

api_status() {
  local method=$1
  local url=$2
  local output=$3
  shift 3
  curl --silent --show-error --output "$output" --write-out '%{http_code}' \
    --request "$method" \
    --header "User-Agent: $user_agent" \
    --header "Authorization: $MODRINTH_TOKEN" \
    "$@" \
    "$url"
}

api_failure_detail() {
  local response=$1
  python3 - "$response" <<'PY'
import json
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
try:
    payload = json.loads(path.read_text(encoding="utf-8"))
except (OSError, UnicodeError, json.JSONDecodeError):
    raise SystemExit(0)
parts = [payload.get("error"), payload.get("description")]
detail = ": ".join(str(value) for value in parts if value)
detail = re.sub(r"\s+", " ", detail).strip()
if detail:
    print(detail[:400])
PY
}

fail_api() {
  local operation=$1
  local status=$2
  local response=$3
  local detail
  detail=$(api_failure_detail "$response")
  if [[ -n "$detail" ]]; then
    fail "$operation returned HTTP $status ($detail)"
  fi
  fail "$operation returned HTTP $status"
}

project_response="$work/project-response.json"
project_status=$(api_status GET "$api/project/$slug" "$project_response")
case "$project_status" in
  200) ;;
  404)
    python3 - "$repository" "$work/project.json" <<'PY'
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
body = (root / "README.md").read_text(encoding="utf-8")
body = body.replace(
    "docs/media/openallay-banner.png",
    "https://raw.githubusercontent.com/nkanf-dev/OpenAllay/main/docs/media/openallay-banner.png",
).replace(
    "README.zh-CN.md",
    "https://github.com/nkanf-dev/OpenAllay/blob/main/README.zh-CN.md",
).replace(
    "docs/development.md",
    "https://github.com/nkanf-dev/OpenAllay/blob/main/docs/development.md",
).replace(
    "LICENSE",
    "https://github.com/nkanf-dev/OpenAllay/blob/main/LICENSE",
)
payload = {
    "project_type": "mod",
    "slug": "openallay",
    "title": "OpenAllay",
    "description": "A modern Minecraft Agent for modded play.",
    "body": body,
    "categories": ["utility"],
    "client_side": "required",
    "server_side": "optional",
    "license_id": "MIT",
    "source_url": "https://github.com/nkanf-dev/OpenAllay",
    "issues_url": "https://github.com/nkanf-dev/OpenAllay/issues",
    "is_draft": True,
    # The v2 API still requires this deprecated field to be present even
    # though versions are uploaded separately after project creation.
    "initial_versions": [],
}
output.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
PY
    project_status=$(api_status POST "$api/project" "$project_response" \
      --form "data=<$work/project.json;type=application/json" \
      --form "icon=@common/src/main/resources/assets/openallay/icon.png;type=image/png")
    [[ "$project_status" == 200 ]] \
      || fail_api 'project creation' "$project_status" "$project_response"
    ;;
  *) fail "project lookup returned HTTP $project_status" ;;
esac

project_id=$(python3 - "$project_response" <<'PY'
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8")).get("id")
if not value:
    raise SystemExit(1)
print(value)
PY
) || fail 'project response did not contain an ID'

project_state=$(python3 - "$project_response" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8")).get("status", "unknown"))
PY
) || fail 'project response did not contain a status'

release_type=release
if [[ "$version" == *-* ]]; then
  release_type=alpha
fi

publish_loader() {
  local loader=$1
  local artifact=$2
  local dependency_project=${3:-}
  local versions_response="$work/${loader}-versions.json"
  local encoded_loaders encoded_versions
  encoded_loaders=$(python3 -c 'import json,sys,urllib.parse; print(urllib.parse.quote(json.dumps([sys.argv[1]])))' "$loader")
  encoded_versions=$(python3 -c 'import json,sys,urllib.parse; print(urllib.parse.quote(json.dumps([sys.argv[1]])))' "$minecraft_version")
  local status
  status=$(api_status GET \
    "$api/project/$project_id/version?loaders=$encoded_loaders&game_versions=$encoded_versions&include_changelog=false" \
    "$versions_response")
  [[ "$status" == 200 ]] \
    || fail_api "$loader version lookup" "$status" "$versions_response"

  if python3 - "$versions_response" "$version" <<'PY'
import json
import sys
versions = json.load(open(sys.argv[1], encoding="utf-8"))
raise SystemExit(0 if any(v.get("version_number") == sys.argv[2] for v in versions) else 1)
PY
  then
    printf 'modrinth_loader=%s status=already_published version=%s\n' "$loader" "$version"
    return
  fi

  [[ -f "$artifact" ]] || fail "missing $loader artifact: $artifact"
  python3 - "$work/$loader-version.json" "$project_id" "$version" \
    "$minecraft_version" "$loader" "$release_type" "$dependency_project" <<'PY'
import json
import pathlib
import sys

output, project_id, version, game_version, loader, release_type, dependency = sys.argv[1:]
dependencies = []
if dependency:
    dependencies.append({"project_id": dependency, "dependency_type": "required"})
payload = {
    "project_id": project_id,
    "name": f"OpenAllay {version} ({loader.title()})",
    "version_number": version,
    "changelog": f"See https://github.com/nkanf-dev/OpenAllay/releases/tag/v{version}",
    "dependencies": dependencies,
    "game_versions": [game_version],
    "version_type": release_type,
    "loaders": [loader],
    "featured": False,
    "status": "listed",
    "file_parts": ["file"],
    "primary_file": "file",
}
pathlib.Path(output).write_text(json.dumps(payload), encoding="utf-8")
PY

  status=$(api_status POST "$api/version" "$work/$loader-response.json" \
    --form "data=<$work/$loader-version.json;type=application/json" \
    --form "file=@$artifact;type=application/java-archive")
  [[ "$status" == 200 ]] \
    || fail_api "$loader version creation" "$status" "$work/$loader-response.json"
  printf 'modrinth_loader=%s status=published version=%s\n' "$loader" "$version"
}

publish_loader fabric \
  "fabric/build/libs/openallay-fabric-${minecraft_version}-${version}.jar" \
  P7dR8mSH
publish_loader neoforge \
  "neoforge/build/libs/openallay-neoforge-${minecraft_version}-${version}.jar"

python3 - "$work/submit.json" "$project_state" <<'PY'
import json
import pathlib
import sys

payload = {
    "client_side": "required",
    "server_side": "optional",
}
if sys.argv[2] == "draft":
    payload["requested_status"] = "approved"
pathlib.Path(sys.argv[1]).write_text(json.dumps(payload), encoding="utf-8")
PY
submit_status=$(api_status PATCH "$api/project/$project_id" "$work/submit-response.json" \
  --header 'Content-Type: application/json' \
  --data-binary "@$work/submit.json")
[[ "$submit_status" == 204 ]] \
  || fail_api 'project metadata update' "$submit_status" "$work/submit-response.json"

printf 'modrinth_project_id=%s\n' "$project_id"
printf 'modrinth_version=%s\n' "$version"
printf 'modrinth_publication=passed\n'
