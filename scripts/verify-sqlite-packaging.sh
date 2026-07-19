#!/usr/bin/env bash
set -euo pipefail

repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
proof_dir=$(mktemp -d "${TMPDIR:-/tmp}/openallay-sqlite-packaging.XXXXXX")
version=$(sed -n 's/^version=//p' "$repository/gradle.properties")
minecraft_version=$(sed -n 's/^minecraft_version=//p' "$repository/gradle.properties")
sqlite_version=$(sed -n 's/^sqlite_jdbc_version=//p' "$repository/gradle.properties")
sqlite_runtime_version=${sqlite_version%.0}
test -n "$version"
test -n "$minecraft_version"
test -n "$sqlite_version"
support_classpath=$(
  cd "$repository"
  ./gradlew -q :common:testClasses :common:printSqliteProofSupportClasspath | tail -n 1
)

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

verify_loader() {
  local loader=$1
  local mod_jar=$2
  local nested_path=$3
  local extracted="$proof_dir/${loader}-sqlite-jdbc.jar"

  unzip -p "$mod_jar" "$nested_path" > "$extracted"
  test -s "$extracted"

  local listing="$proof_dir/${loader}-sqlite-contents.txt"
  jar tf "$extracted" > "$listing"
  grep -Fxq 'org/sqlite/JDBC.class' "$listing"
  for target in \
    Linux/x86_64 Linux/aarch64 \
    Mac/x86_64 Mac/aarch64 \
    Windows/x86_64 Windows/aarch64; do
    grep -q "^org/sqlite/native/${target}/" "$listing"
  done

  local probe
  probe=$(java -cp "$extracted:$support_classpath" \
    dev.openallay.guide.history.SqliteRuntimeCompatibilityTest "$extracted")
  grep -q "^sqlite=${sqlite_runtime_version//./\\.} source=" <<< "$probe"

  printf '%s mod_sha256=%s driver_sha256=%s %s\n' \
    "$loader" "$(sha256 "$mod_jar")" "$(sha256 "$extracted")" "$probe"
}

verify_loader \
  fabric \
  "$repository/fabric/build/libs/openallay-fabric-${minecraft_version}-${version}.jar" \
  "META-INF/jars/sqlite-jdbc-${sqlite_version}.jar"
verify_loader \
  neoforge \
  "$repository/neoforge/build/libs/openallay-neoforge-${minecraft_version}-${version}.jar" \
  "META-INF/jarjar/sqlite-jdbc-${sqlite_version}.jar"

printf 'native_targets=Linux/x86_64,Linux/aarch64,Mac/x86_64,Mac/aarch64,Windows/x86_64,Windows/aarch64\n'
printf 'proof_directory=%s\n' "$proof_dir"
