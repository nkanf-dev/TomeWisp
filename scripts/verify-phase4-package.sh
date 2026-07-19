#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

fabric_jar="$(find fabric/build/libs -maxdepth 1 -type f \
  -name 'openallay-fabric-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"
neoforge_jar="$(find neoforge/build/libs -maxdepth 1 -type f \
  -name 'openallay-neoforge-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print -quit)"

test -n "$fabric_jar"
test -n "$neoforge_jar"

fabric_entries="$(jar tf "$fabric_jar")"
neoforge_entries="$(jar tf "$neoforge_jar")"

for entry in \
  'dev/openallay/guide/history/GuideHistoryPartition.class' \
  'dev/openallay/guide/semantic/SemanticMessageParser.class'; do
  grep -Fqx "$entry" <<<"$fabric_entries"
  grep -Fqx "$entry" <<<"$neoforge_entries"
done

for dependency in \
  'commonmark-0.28.0.jar' \
  'commonmark-ext-gfm-tables-0.28.0.jar' \
  'sqlite-jdbc-3.50.3.0.jar'; do
  grep -Fq "META-INF/jars/$dependency" <<<"$fabric_entries"
  grep -Fq "META-INF/jarjar/$dependency" <<<"$neoforge_entries"
done

unzip -p "$fabric_jar" fabric.mod.json | python3 -c \
  'import json,sys; data=json.load(sys.stdin); assert data["environment"] == "*"'
python3 -c \
  'import json; [json.load(open(path, encoding="utf-8")) for path in ("common/src/main/resources/assets/openallay/lang/en_us.json", "common/src/main/resources/assets/openallay/lang/zh_cn.json")]'

grep -Fq 'SCHEMA_VERSION = 5' \
  common/src/main/java/dev/openallay/guide/history/GuideHistoryPartition.java
if rg -n -i 'migrate|migration|upgradeSchema' \
  common/src/main/java/dev/openallay/guide/history >/dev/null; then
  printf '%s\n' 'Unexpected history migration surface found' >&2
  exit 1
fi

if git grep -nE 'sk-[A-Za-z0-9]{20,}|Bearer[[:space:]]+[A-Za-z0-9_-]{20,}' \
  -- . ':!docs/verification' >/dev/null; then
  printf '%s\n' 'Credential-like literal found in tracked source' >&2
  exit 1
fi

printf 'fabric_sha256=%s\n' "$(shasum -a 256 "$fabric_jar" | awk '{print $1}')"
printf 'neoforge_sha256=%s\n' "$(shasum -a 256 "$neoforge_jar" | awk '{print $1}')"
printf '%s\n' 'phase4_package_verification=passed'
