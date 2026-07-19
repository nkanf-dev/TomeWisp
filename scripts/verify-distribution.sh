#!/usr/bin/env bash
set -euo pipefail

repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
version=$(sed -n 's/^version=//p' "$repository/gradle.properties")
minecraft_version=$(sed -n 's/^minecraft_version=//p' "$repository/gradle.properties")

fail() {
  printf 'distribution verification failed: %s\n' "$1" >&2
  exit 1
}

test -n "$version" || fail 'version is missing from gradle.properties'
test -n "$minecraft_version" || fail 'minecraft_version is missing from gradle.properties'

fabric_name="openallay-fabric-${minecraft_version}-${version}.jar"
neoforge_name="openallay-neoforge-${minecraft_version}-${version}.jar"

if (( $# > 1 )); then
  fail 'usage: verify-distribution.sh [staged-release-directory]'
fi

if (( $# == 1 )); then
  distribution=$(cd "$1" && pwd)
  shopt -s nullglob
  staged_jars=("$distribution"/*.jar)
  shopt -u nullglob
  (( ${#staged_jars[@]} == 2 )) \
    || fail "staged release must contain exactly two JARs"
  fabric_jar="$distribution/$fabric_name"
  neoforge_jar="$distribution/$neoforge_name"
  test -f "$fabric_jar" && test -f "$neoforge_jar" \
    || fail "staged release must contain only $fabric_name and $neoforge_name"
else
  fabric_jar="$repository/fabric/build/libs/$fabric_name"
  neoforge_jar="$repository/neoforge/build/libs/$neoforge_name"
fi

verify_zip() {
  local jar=$1
  local listing
  local legacy_namespace='tome''wisp'
  test -s "$jar" || fail "missing production artifact: $jar"
  unzip -tq "$jar" >/dev/null || fail "invalid JAR archive: $jar"
  listing=$(jar tf "$jar")
  if grep -Eiq "(^|/)${legacy_namespace}(/|\\.|$)|^dev/${legacy_namespace}/" <<< "$listing"; then
    fail "legacy package branding is present in $jar"
  fi
  grep -Fqx 'dev/openallay/OpenAllayBootstrap.class' <<< "$listing" \
    || fail "OpenAllay common bootstrap is missing from $jar"
}

verify_zip "$fabric_jar"
verify_zip "$neoforge_jar"

python3 - "$fabric_jar" "$version" <<'PY'
import json
import sys
import zipfile

path, version = sys.argv[1:]
with zipfile.ZipFile(path) as archive:
    metadata = json.loads(archive.read("fabric.mod.json"))
assert metadata["id"] == "openallay", metadata
assert metadata["name"] == "OpenAllay", metadata
assert metadata["version"] == version, metadata
PY

python3 - "$neoforge_jar" "$version" <<'PY'
import re
import sys
import zipfile

path, version = sys.argv[1:]
with zipfile.ZipFile(path) as archive:
    metadata = archive.read("META-INF/neoforge.mods.toml").decode("utf-8")
mods = metadata.split("[[mods]]", 1)[1].split("[[dependencies.", 1)[0]
values = dict(
    re.findall(
        r'(?m)^\s*(modId|displayName|version)\s*=\s*"([^"]+)"', mods
    )
)
assert values.get("modId") == "openallay", values
assert values.get("displayName") == "OpenAllay", values
assert values.get("version") == version, values
PY

printf 'fabric_artifact=%s\n' "$fabric_name"
printf 'neoforge_artifact=%s\n' "$neoforge_name"
printf 'distribution_verification=passed\n'
