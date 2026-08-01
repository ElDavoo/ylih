#!/usr/bin/env python3
"""Check that R8 ran and left the classes nothing in this repository can reach statically.

Unit tests never see minified code: they run on the JVM against unshrunk classes, so R8 is not
part of any test the build has. That leaves the one failure mode R8 actually has here silent —
a class reached only by name, stripped or renamed, which compiles and tests green and then fails
on a device.

Two invariants, both read out of R8's own mapping.txt:

1. R8 ran at all. AGP writes mapping.txt only when the release build is optimized, so its absence
   means someone turned the `optimization` block back off — which is what Play Console complains
   about ("No R8 metadata included") and what quietly re-inflates the APK from 2.8 MB to 11.5 MB.

2. Every class below survived shrinking *and* kept its exact name. Renaming is the subtler half:
   a renamed class is still in the APK, so nothing crashes at install time, and the damage shows
   up only when something outside the APK tries to name it.

The one that is genuinely load-bearing is HeartbeatWorker. WorkManager stores the worker's class
name as a string in its own database, so a heartbeat enqueued by one version is instantiated by
name after the update. Rename the class between versions and the work does not crash — it fails
to instantiate, and the heartbeat that bounds the damage of a missed disconnect silently stops
for exactly the installs that already had one scheduled. It is kept today by androidx.work's
consumer rules, which is a library's decision rather than ours: a dependency bump can change it,
and this check is what would notice.

Room's YlihDatabase_Impl is the same shape of dependency on a consumer rule — Room derives the
impl's name from the @Database class and loads it reflectively, so both names have to hold.

The manifest components are cheaper insurance. AAPT2 generates keep rules for anything named in
the merged manifest, so they should never move; they are listed because "should never" is worth
asserting when the cost is one line.

Not checked here, because it does not depend on R8: the JSON export keys. kotlinx.serialization
bakes descriptor element names into the generated serializer as string constants at compile time,
so obfuscating the Kotlin properties cannot move them — verified by finding `formatVersion`,
`disconnectedAt` and `retireReason` as literals in the optimized dex.

Usage: r8-keep-check.py [app/build/outputs/mapping/classicRelease]
"""

import re
import sys
from pathlib import Path

# Class -> why its name, not merely its existence, has to survive.
KEEP_BY_NAME = {
    "it.eldavo.ylih.tracking.HeartbeatWorker":
        "WorkManager persists the worker's class name in its own database, so a rename between "
        "versions silently stops heartbeats that were already scheduled",
    "it.eldavo.ylih.data.YlihDatabase":
        "Room derives the generated implementation's name from this class",
    "it.eldavo.ylih.data.YlihDatabase_Impl":
        "Room loads the generated implementation by name",
    "it.eldavo.ylih.YlihApp":
        "android:name on <application> in the merged manifest",
    "it.eldavo.ylih.MainActivity":
        "named in the merged manifest",
    "it.eldavo.ylih.tracking.TrackingService":
        "named in the merged manifest",
    "it.eldavo.ylih.tracking.BtConnectionReceiver":
        "named in the merged manifest, and the process is started by this broadcast",
    "it.eldavo.ylih.tracking.BootReceiver":
        "named in the merged manifest, and the process is started by this broadcast",
}

# A class line in mapping.txt: `<original> -> <obfuscated>:` at column 0. Members are indented,
# so anchoring at the start is what keeps this from matching a method whose name contains one.
CLASS_LINE = re.compile(r"^(\S+) -> (\S+):$", re.M)


def main() -> int:
    mapping_dir = Path(sys.argv[1] if len(sys.argv) > 1
                       else "app/build/outputs/mapping/classicRelease")
    mapping = mapping_dir / "mapping.txt"
    problems = []

    if not mapping.is_file():
        print(f"::error::{mapping} does not exist — R8 did not run for this variant. The release "
              f"build type needs `optimization {{ enable = true }}`; without it Play Console "
              f"reports the upload as unoptimized and the APK is roughly four times larger.",
              file=sys.stderr)
        return 1

    renames = dict(CLASS_LINE.findall(mapping.read_text(encoding="utf-8")))
    if not renames:
        problems.append(f"{mapping} parsed to zero classes — the format has changed and this "
                        f"check is no longer reading anything")

    for name, why in sorted(KEEP_BY_NAME.items()):
        actual = renames.get(name)
        if actual is None:
            problems.append(f"{name} is absent from mapping.txt, so R8 removed it — {why}")
        elif actual != name:
            problems.append(f"{name} was renamed to {actual} — {why}")

    for problem in problems:
        print(f"::error file=app/build.gradle.kts::{problem}", file=sys.stderr)

    if problems:
        print(f"\n{len(problems)} R8 keep problem(s); a keep rule in app/src/main/keepRules/ "
              f"is the fix", file=sys.stderr)
        return 1

    print(f"R8 ran ({len(renames)} classes in the output) and all {len(KEEP_BY_NAME)} "
          f"reflectively-reached classes kept their names")
    return 0


if __name__ == "__main__":
    sys.exit(main())
