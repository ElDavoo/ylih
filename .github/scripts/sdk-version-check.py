#!/usr/bin/env python3
"""Assert that the Android SDK versions agree everywhere they are written down.

`compileSdk` and `buildToolsVersion` live in `app/build.gradle.kts`, and the same two numbers are
repeated in `flake.nix` (which pins the dev shell's SDK) and across the workflows (which install
and cache it). `gradle/libs.versions.toml` says "keep in sync" and nothing checked that anyone had.

Bumping one and not the others does not fail loudly: the build asks AGP to download whatever it is
missing, so a stale workflow pin quietly costs a download per run instead of a cache hit, and a
stale `flake.nix` leaves the dev shell building against a different platform than CI does. This
takes a second and says which file disagrees.

The F-Droid workflow is exempt on purpose: it names the versions in order to assert that they are
*absent*, because its buildserver preinstalls nothing past 33 and the whole point of that job is
that AGP fetches them itself. See docs/fdroid.md §2.

    python3 .github/scripts/sdk-version-check.py
"""

from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]

# Files that must agree, and how to read the two versions out of each. A file that mentions
# neither is a file that has stopped being part of this, and is reported rather than skipped.
SOURCES = [
    "app/build.gradle.kts",
    "flake.nix",
    ".github/workflows/android-ci.yml",
    ".github/workflows/android-release.yml",
    ".github/workflows/nix.yml",
]

PLATFORM = re.compile(r"(?:compileSdk\s*=\s*(\d+)|android-(\d+\.\d+)|platformVersion\s*=\s*\"(\d+\.\d+)\"|platforms;android-(\d+\.\d+))")
BUILD_TOOLS = re.compile(r"(?:buildToolsVersion\s*=\s*\"([\d.]+)\"|build-tools[/;]([\d.]+))")


def versions(text: str) -> tuple[set[str], set[str]]:
    """Every platform and build-tools version named in *text*, normalised to bare numbers."""
    platforms = {
        # `compileSdk = 37` and `android-37.0` are the same platform written two ways.
        next(g for g in match.groups() if g).split(".")[0]
        for match in PLATFORM.finditer(text)
    }
    tools = {next(g for g in match.groups() if g) for match in BUILD_TOOLS.finditer(text)}
    return platforms, tools


def main() -> int:
    problems: list[str] = []
    seen: dict[str, tuple[set[str], set[str]]] = {}

    for name in SOURCES:
        path = ROOT / name
        if not path.exists():
            problems.append(f"{name}: missing")
            continue
        platforms, tools = versions(path.read_text())
        if not platforms and not tools:
            problems.append(f"{name}: names no SDK version — has it stopped pinning one?")
            continue
        seen[name] = (platforms, tools)

    for label, index in (("platform", 0), ("build-tools", 1)):
        everywhere = {v for value in seen.values() for v in value[index]}
        if len(everywhere) > 1:
            problems.append(
                f"{label} versions disagree: "
                + ", ".join(
                    f"{name} says {sorted(value[index])}"
                    for name, value in sorted(seen.items())
                    if value[index]
                )
            )

    for problem in problems:
        print(f"::error::{problem}")
    if problems:
        return 1

    platform = sorted({v for value in seen.values() for v in value[0]})
    tools = sorted({v for value in seen.values() for v in value[1]})
    print(f"OK: platform {platform[0]}, build-tools {tools[0]}, across {len(seen)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
