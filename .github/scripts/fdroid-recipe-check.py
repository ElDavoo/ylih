#!/usr/bin/env python3
"""Check metadata/it.eldavo.ylih.yml against the source it claims to build.

`fdroid lint` validates the recipe as a document — field names, category names, YAML style — but
has no idea whether it still describes *this* repository. That drift happens in practice: a
versionCode bumped in build.gradle.kts with no matching build entry, a `commit:` naming a hash no
tag points at, a changelog file F-Droid will look for and not find. All of these fail in
fdroiddata days later, where the feedback loop is a merge request rather than a job log.

One asymmetry: the recipe may lag the source. Between bumping versionCode and tagging the release
there is no build entry for the new version -- the normal state of a release PR, not an error. So
the checks run from the recipe outwards -- every build entry must describe something real -- and
never demand a build entry for whatever build.gradle.kts currently says.
"""

import re
import subprocess
import sys
from pathlib import Path

import yaml


def fail(problems, msg):
    problems.append(msg)


def peel_tag(root, tag):
    """The commit `tag` points at, or None if this checkout has no such tag.

    `^{}` peels an annotated tag down to its commit, which a recipe's `commit:` must hold -- the
    tag object's own hash names something F-Droid cannot check out as a tree.
    """
    result = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "-q", "--verify", f"refs/tags/{tag}^{{}}"],
        capture_output=True, text=True,
    )
    return result.stdout.strip() if result.returncode == 0 else None


def main():
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    recipe_path = root / "metadata" / "it.eldavo.ylih.yml"
    gradle_path = root / "app" / "build.gradle.kts"

    recipe = yaml.safe_load(recipe_path.read_text(encoding="utf-8"))
    gradle = gradle_path.read_text(encoding="utf-8")

    problems = []

    # F-Droid extracts versionName/versionCode with a regex and cannot evaluate anything
    # computed, so read them the same blunt way instead of running Gradle.
    src_code = re.search(r"^\s*versionCode = (\d+)$", gradle, re.M)
    src_name = re.search(r'^\s*versionName = "([^"]+)"$', gradle, re.M)
    if not src_code or not src_name:
        fail(problems, "could not read versionCode/versionName out of app/build.gradle.kts -- "
                       "if they became computed, F-Droid cannot read them either")
        print_problems(problems)
        return 1
    src_code, src_name = int(src_code.group(1)), src_name.group(1)

    # Scoped to the productFlavors block: signingConfigs also uses create("release"), and an
    # unscoped flavor list would quietly accept a recipe naming a build type.
    flavors_block = re.search(r"^(\s*)productFlavors\s*\{$(.*?)^\1\}$", gradle, re.M | re.S)
    flavors = set(re.findall(r'create\("(\w+)"\)', flavors_block.group(2))) if flavors_block else set()
    if not flavors:
        fail(problems, "could not find any productFlavors in app/build.gradle.kts")

    builds = recipe.get("Builds") or []
    if not builds:
        fail(problems, "no Builds: entries -- F-Droid would publish nothing")

    for build in builds:
        vname, vcode = build.get("versionName"), build.get("versionCode")
        where = f"build {vname} ({vcode})"

        # `commit:` holds the tag's full hash, not the tag name -- an F-Droid reviewer required
        # it, since a tag can move but a hash can't. So this has to *resolve* the hash rather
        # than string-compare it: it must be the one v<versionName> currently points at.
        # UpdateCheckMode (`Tags ^v[0-9.]+$`) and the release asset name hang off that same tag
        # name too, even though the recipe no longer spells it out.
        commit = str(build.get("commit") or "")
        if not re.fullmatch(r"[0-9a-f]{40}", commit):
            fail(problems, f"{where}: commit is {build.get('commit')!r}, expected the full "
                           f"40-character hash of tag v{vname} -- fdroiddata rejects a tag or "
                           f"branch name here (`git rev-parse v{vname}^{{}}`)")
        else:
            tagged = peel_tag(root, f"v{vname}")
            if tagged is None:
                fail(problems, f"{where}: no tag v{vname} in this checkout, so the commit cannot "
                               f"be confirmed to be the one F-Droid will find -- push the tag, or "
                               f"fetch it if this clone is shallow")
            elif tagged != commit:
                fail(problems, f"{where}: commit is {commit}, but v{vname} points at {tagged}")

        if build.get("subdir") != "app":
            fail(problems, f"{where}: subdir is {build.get('subdir')!r}, expected 'app'")

        # `gradle: [classic]` makes fdroidserver run assembleClassicRelease; a flavor that no
        # longer exists fails as a missing task, which reads as a Gradle bug.
        for flavor in build.get("gradle") or []:
            if flavor not in flavors:
                fail(problems, f"{where}: gradle flavor {flavor!r} is not a productFlavor in "
                               f"app/build.gradle.kts (has: {', '.join(sorted(flavors))})")

        # A changelog only appears once F-Droid has built that versionCode; en-US is the
        # fallback for every other locale, so it's the one that must exist.
        changelog = root / "fastlane/metadata/android/en-US/changelogs" / f"{vcode}.txt"
        if not changelog.exists():
            fail(problems, f"{where}: {changelog.relative_to(root)} is missing")

        if vcode > src_code:
            fail(problems, f"{where}: versionCode {vcode} is ahead of app/build.gradle.kts "
                           f"({src_code}) -- the recipe may lag the source, never lead it")

    if builds:
        latest = max(builds, key=lambda b: b["versionCode"])
        if recipe.get("CurrentVersionCode") != latest["versionCode"]:
            fail(problems, f"CurrentVersionCode is {recipe.get('CurrentVersionCode')}, expected "
                           f"{latest['versionCode']} (the newest Builds entry)")
        if recipe.get("CurrentVersion") != latest["versionName"]:
            fail(problems, f"CurrentVersion is {recipe.get('CurrentVersion')!r}, expected "
                           f"{latest['versionName']!r} (the newest Builds entry)")

    # Reproducible builds: both fields must be present, or absent, together. One without the
    # other isn't a half-configuration -- it's a verification that can't run.
    binaries = recipe.get("Binaries")
    keys = recipe.get("AllowedAPKSigningKeys")
    if bool(binaries) != bool(keys):
        fail(problems, "Binaries: and AllowedAPKSigningKeys: must be set together -- one alone "
                       "either verifies against nothing or verifies nothing")

    if keys:
        if not re.fullmatch(r"[0-9a-f]{64}", str(keys)):
            fail(problems, f"AllowedAPKSigningKeys is {keys!r}, expected 64 lowercase hex "
                           "characters (the SHA-256 of the signing certificate, no colons)")

    if binaries:
        # %v expands to versionName. The release workflow builds the asset name from the tag
        # ('v' + versionName), so the URL must spell that prefix out itself.
        if "%v" not in binaries:
            fail(problems, "Binaries: has no %v, so every version would be verified against the "
                           "same downloaded APK")
        expected = ("https://github.com/ElDavoo/ylih/releases/download/"
                    "v%v/ylih-v%v-classic.apk")
        if binaries != expected:
            fail(problems, f"Binaries: is {binaries!r}, expected {expected!r} -- this has to "
                           "match how android-release.yml names the uploaded asset")

    print_problems(problems)
    if not problems:
        print(f"recipe describes {len(builds)} build(s); "
              f"source is {src_name} ({src_code}); flavors: {', '.join(sorted(flavors))}")
    return 1 if problems else 0


def print_problems(problems):
    for problem in problems:
        print(f"::error file=metadata/it.eldavo.ylih.yml::{problem}")
    if problems:
        print(f"\n{len(problems)} problem(s) in metadata/it.eldavo.ylih.yml", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
