#!/usr/bin/env python3
"""Check fastlane/metadata against the store character limits.

Both stores read the same directory, and both silently truncate rather than reject: an
over-long summary simply arrives on f-droid.org and Play with the end missing, and nobody
notices until someone reads their own listing in a language they do not speak. The limits
below are F-Droid's (fdroidserver's `char_limits` default, which Play matches), so the
tighter of the two is what gets enforced.

The other half of the job is the changelog filename: F-Droid only shows `<versionCode>.txt`
if that versionCode is one it has actually built, so a file named for the wrong number is
not an error anywhere -- it is just a changelog that never appears.

Usage: listing-metadata-check.py [fastlane/metadata/android] [expected versionCode]
"""

import re
import sys
from pathlib import Path

# fdroidserver/common.py, config['char_limits']. Play's limits for the same fields are the
# same or looser, so satisfying these satisfies both.
LIMITS = {
    "title.txt": 50,
    "short_description.txt": 80,
    "full_description.txt": 4000,
}
CHANGELOG_LIMIT = 500

# F-Droid falls back to en-US for any locale that is missing text, so that one has to be whole.
FALLBACK_LOCALE = "en-US"


def check(root: Path, version_code: int | None) -> list[str]:
    problems = []
    locales = sorted(d for d in root.iterdir() if d.is_dir())
    if not locales:
        return [f"{root}: no locale directories found"]

    if not (root / FALLBACK_LOCALE).is_dir():
        problems.append(f"{root}: no {FALLBACK_LOCALE}/ — F-Droid has nothing to fall back to")

    for locale in locales:
        rel = locale
        texts = {f.name for f in locale.glob("*.txt")}
        if not texts and not any(locale.glob("changelogs/*.txt")):
            problems.append(f"{rel}: empty locale directory")
            continue

        for name, limit in LIMITS.items():
            path = locale / name
            if not path.is_file():
                # Only the fallback locale has to be complete; a partial translation is
                # legitimate and F-Droid fills the gaps from en-US.
                if locale.name == FALLBACK_LOCALE:
                    problems.append(f"{rel}/{name}: missing from the fallback locale")
                continue
            length = len(path.read_text(encoding="utf-8").strip())
            if length > limit:
                problems.append(f"{rel}/{name}: {length} chars, limit {limit}")

        for path in sorted(locale.glob("changelogs/*.txt")):
            if not re.fullmatch(r"[0-9]+", path.stem):
                problems.append(
                    f"{rel}/changelogs/{path.name}: not named for a versionCode"
                )
                continue
            if version_code is not None and int(path.stem) > version_code:
                problems.append(
                    f"{rel}/changelogs/{path.name}: no such versionCode (current is {version_code})"
                )
            length = len(path.read_text(encoding="utf-8").strip())
            if length > CHANGELOG_LIMIT:
                problems.append(
                    f"{rel}/changelogs/{path.name}: {length} chars, limit {CHANGELOG_LIMIT}"
                )

    return problems


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "fastlane/metadata/android")
    version_code = int(sys.argv[2]) if len(sys.argv) > 2 else None
    if not root.is_dir():
        print(f"{root}: not a directory", file=sys.stderr)
        return 2

    problems = check(root, version_code)
    for p in problems:
        print(f"error: {p}", file=sys.stderr)
    if problems:
        print(f"\n{len(problems)} listing metadata problem(s)", file=sys.stderr)
        return 1

    locales = sum(1 for d in root.iterdir() if d.is_dir())
    print(f"listing metadata OK — {locales} locales within the store limits")
    return 0


if __name__ == "__main__":
    sys.exit(main())
