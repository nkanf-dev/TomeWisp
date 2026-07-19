#!/usr/bin/env bash
set -euo pipefail

repository=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repository"

fail() {
  printf 'release tag verification failed: %s\n' "$1" >&2
  exit 1
}

if (( $# < 1 || $# > 2 )); then
  fail 'usage: verify-release-tag.sh <tag> [expected-commit]'
fi

tag=$1
expected_commit=${2:-}
semver='v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-((0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*))?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?'
[[ "$tag" =~ ^${semver}$ ]] || fail "tag is not strict SemVer with a v prefix: $tag"

tag_ref="refs/tags/$tag"
[[ $(git cat-file -t "$tag_ref" 2>/dev/null || true) == tag ]] \
  || fail "tag must exist locally and be annotated: $tag"
tag_commit=$(git rev-parse "${tag_ref}^{commit}")

if [[ -n "$expected_commit" ]]; then
  expected_commit=$(git rev-parse "${expected_commit}^{commit}")
  [[ "$tag_commit" == "$expected_commit" ]] \
    || fail "tag target $tag_commit does not match expected commit $expected_commit"
fi

version=$(sed -n 's/^version=//p' gradle.properties)
test -n "$version" || fail 'version is missing from gradle.properties'
[[ "$version" == "${tag#v}" ]] \
  || fail "tag version ${tag#v} does not match Gradle version $version"

release_channel=stable
if [[ "$version" == *-* ]]; then
  release_channel=prerelease
fi

git show-ref --verify --quiet refs/remotes/origin/main \
  || fail 'origin/main is unavailable; fetch full history before verification'
git merge-base --is-ancestor "$tag_commit" refs/remotes/origin/main \
  || fail "$tag is not reachable from origin/main"

if [[ -n "${GITHUB_REPOSITORY:-}" || -n "${GH_TOKEN:-}" ]]; then
  [[ -n "${GITHUB_REPOSITORY:-}" && -n "${GH_TOKEN:-}" ]] \
    || fail 'GITHUB_REPOSITORY and GH_TOKEN must both be set for release vacancy verification'
  response=$(mktemp "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/openallay-release-response.XXXXXX")
  trap 'rm -f "$response"' EXIT
  status=$(curl --silent --show-error --output "$response" --write-out '%{http_code}' \
    --header 'Accept: application/vnd.github+json' \
    --header "Authorization: Bearer ${GH_TOKEN}" \
    --header 'X-GitHub-Api-Version: 2022-11-28' \
    "https://api.github.com/repos/${GITHUB_REPOSITORY}/releases/tags/${tag}") \
    || fail 'GitHub release vacancy request failed'
  case "$status" in
    404) ;;
    200) fail "a GitHub release already exists for $tag" ;;
    *) fail "GitHub release vacancy request returned HTTP $status" ;;
  esac
fi

printf 'release_tag=%s\n' "$tag"
printf 'release_version=%s\n' "$version"
printf 'release_channel=%s\n' "$release_channel"
printf 'release_commit=%s\n' "$tag_commit"
printf 'release_tag_verification=passed\n'
