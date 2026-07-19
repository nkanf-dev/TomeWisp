#!/usr/bin/env python3
"""Generate deterministic OpenAllay release notes from an annotated SemVer tag."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path


SEMVER = re.compile(
    r"^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)


@dataclass(frozen=True)
class Commit:
    sha: str
    subject: str
    author_name: str
    author_email: str


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], check=True, text=True, stdout=subprocess.PIPE
    ).stdout.strip()


def annotated_semver_tags(commit: str) -> list[str]:
    tags: list[str] = []
    for tag in git("tag", "--merged", commit).splitlines():
        if not SEMVER.fullmatch(tag):
            continue
        if git("cat-file", "-t", f"refs/tags/{tag}") == "tag":
            tags.append(tag)
    return tags


def previous_tag(current_tag: str, current_commit: str) -> str | None:
    candidates = [
        tag
        for tag in annotated_semver_tags(current_commit)
        if tag != current_tag
    ]
    if not candidates:
        return None
    return min(
        candidates,
        key=lambda tag: int(git("rev-list", "--count", f"{tag}..{current_commit}")),
    )


def commits(revision_range: str) -> list[Commit]:
    record_separator = "\x1e"
    field_separator = "\x1f"
    output = git(
        "log",
        "--reverse",
        f"--format=%H{field_separator}%s{field_separator}%aN{field_separator}%aE{record_separator}",
        revision_range,
    )
    result: list[Commit] = []
    for record in output.split(record_separator):
        fields = record.strip().split(field_separator)
        if len(fields) == 4:
            result.append(Commit(*fields))
    return result


def clean(text: str) -> str:
    return " ".join(text.replace("<", "&lt;").replace(">", "&gt;").split())


def github_generated_notes(tag: str, previous: str | None, repository: str) -> str:
    request = [
        "gh",
        "api",
        "--method",
        "POST",
        f"repos/{repository}/releases/generate-notes",
        "-f",
        f"tag_name={tag}",
    ]
    if previous:
        request.extend(("-f", f"previous_tag_name={previous}"))
    response = subprocess.run(
        request,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return str(json.loads(response.stdout).get("body", "")).strip()


def category(subject: str) -> str:
    lowered = subject.lower()
    if "!:" in lowered or "breaking change" in lowered:
        return "Breaking changes"
    prefix = lowered.split("(", 1)[0].split(":", 1)[0]
    return {
        "feat": "Features",
        "fix": "Fixes",
        "docs": "Documentation",
        "perf": "Performance",
        "refactor": "Maintenance",
        "build": "Maintenance",
        "ci": "Maintenance",
        "chore": "Maintenance",
        "test": "Maintenance",
    }.get(prefix, "Other changes")


def render(tag: str, repository: str | None) -> str:
    current_commit = git("rev-parse", f"refs/tags/{tag}^{{commit}}")
    previous = previous_tag(tag, current_commit)
    revision_range = f"{previous}..{current_commit}" if previous else current_commit
    history = commits(revision_range)
    grouped: dict[str, list[Commit]] = {}
    for commit in history:
        grouped.setdefault(category(commit.subject), []).append(commit)

    lines = [f"# OpenAllay {tag}", ""]
    if repository:
        if not os.environ.get("GH_TOKEN"):
            raise RuntimeError("GH_TOKEN is required to generate GitHub release notes")
        generated = github_generated_notes(tag, previous, repository)
        lines.extend((generated or "## Changes\n\nNo pull-request notes were generated.", ""))
    else:
        lines.extend(("## Changes", ""))
        for heading in (
            "Breaking changes",
            "Features",
            "Fixes",
            "Performance",
            "Documentation",
            "Maintenance",
            "Other changes",
        ):
            entries = grouped.get(heading)
            if not entries:
                continue
            lines.extend((f"### {heading}", ""))
            for commit in entries:
                lines.append(f"- {clean(commit.subject)} (`{commit.sha[:7]}`)")
            lines.append("")

    lines.extend(("## Commits", ""))
    for commit in history:
        sha = commit.sha[:7]
        reference = (
            f"[`{sha}`](https://github.com/{repository}/commit/{commit.sha})"
            if repository
            else f"`{sha}`"
        )
        lines.append(f"- {reference} {clean(commit.subject)}")
    if not history:
        lines.append("- No commits in this release range.")
    lines.append("")

    contributors = sorted(
        {clean(commit.author_name) for commit in history}, key=str.casefold
    )
    lines.extend(("## Contributors", ""))
    lines.extend(f"- {name}" for name in contributors)
    if not contributors:
        lines.append("- No contributors in this release range.")
    lines.append("")

    if repository and previous:
        lines.extend(
            (
                f"**Full changelog:** https://github.com/{repository}/compare/{previous}...{tag}",
                "",
            )
        )
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    parser.add_argument("--repository")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not SEMVER.fullmatch(args.tag):
        parser.error("tag must be strict SemVer with a v prefix")
    args.output.write_text(render(args.tag, args.repository), encoding="utf-8")


if __name__ == "__main__":
    main()
