#!/usr/bin/env python3
"""Turn a JaCoCo report.xml into a readable summary.

Printed to stdout and appended to the job summary under Actions, so the number shows on the
workflow run page without opening the HTML artifact. Not a third-party action: no token, no
network, no service that sees the repository.

The report covers more than this repository wrote: Room's KSP output (`*Dao_Impl`, roughly a
fifth of the instructions), plus stdlib and coroutines sources the compiler inlines into our
classes and attributes to our packages — `SafeCollector.common.kt`, `Emitters.kt`, `LazyDsl.kt`,
`Comparisons.kt`. Nobody here can test that, so the headline number counts only classes whose
source file exists under the source tree. What's dropped is printed underneath rather than
hidden; AGP's coverage task has no exclusion setting, so the report on disk still has everything.

`--min-<metric>=N` turns the summary into a gate: the run fails if that counter, over authored
code, falls below N percent. CI sets instruction 95 and line 98, each a little under where the
suite sits, so the gate catches a regression rather than noise. Line is one number for both
flavors and must clear the lower: `classic` measures 98.9 against `play`'s 99.1, for the minSdk 26
reason CLAUDE.md gives at the coverage gate.

Branch is gated differently. It sits at 77.0 but is gated at 70, deliberately loose: the counter
doesn't measure what the other two do, since the Compose compiler emits a `$changed`/
default-argument bitmask branch per composable parameter that no test drives — about two thirds
of the missed branches. It moves when a composable gains an argument, not when testing gets
worse, so a floor just under it would flag ordinary UI work as a regression. 70 leaves roughly
eighty missed branches of headroom, enough that a breach means something real. Raising it would
mean testing compiler-generated dispatch; if it fails, check what it failed *on* before adding
tests to appease it.

Method and class are ungated: at ~95% they sit six classes above a 90 floor, close enough that
ordinary work would trip them without coverage actually regressing.

Usage: coverage-summary.py <report.xml> [label] [--sources=DIR] [--min-instruction=N ...]
"""

import os
import sys
import xml.etree.ElementTree as ET

# Ordered by reading priority: the totals people quote first, the rest for detail.
COUNTERS = ("INSTRUCTION", "LINE", "BRANCH", "METHOD", "CLASS")

DEFAULT_SOURCES = "app/src"

# Floors arrive as --min-<metric>=N: what's gated is whatever CI passes, not anything baked in
# here. See the module docstring for what the numbers mean.
MIN_PREFIX = "--min-"

# A line belongs to its source file, not each class compiled from it; taking LINE from the
# classes would count a file once per Compose lambda it contains.
FROM_CLASS = ("INSTRUCTION", "BRANCH", "METHOD", "CLASS")
FROM_SOURCEFILE = ("LINE",)

MARKER = os.sep + "java" + os.sep


def authored(sources):
    """Every `<package>/<file.kt>` with a file behind it, across all source sets.

    Keyed the way JaCoCo names things — `package` is a slash-separated path, `sourcefilename` a
    bare file name — so a lookup needs no guessing at variant directories.
    """
    found = set()
    for root, _, files in os.walk(sources):
        head = root + os.sep
        if MARKER not in head:
            continue
        package = head.split(MARKER, 1)[1].replace(os.sep, "/").strip("/")
        for name in files:
            found.add(f"{package}/{name}" if package else name)
    return found


def add(into, node, only):
    for counter in node.findall("counter"):
        kind = counter.get("type")
        if kind not in only:
            continue
        covered, missed = int(counter.get("covered")), int(counter.get("missed"))
        got = into.setdefault(kind, [0, 0])
        got[0] += covered
        got[1] += covered + missed


def pct(covered, total):
    return f"{100.0 * covered / total:.1f}%" if total else "n/a"


def tally(root, ours):
    """Totals over authored code, the same over everything else, and the per-package split."""
    mine, theirs, packages = {}, {}, {}
    for package in root.findall("package"):
        name = package.get("name")
        for node in package.findall("class") + package.findall("sourcefile"):
            if node.tag == "sourcefile":
                source, counters = node.get("name"), FROM_SOURCEFILE
            else:
                source, counters = node.get("sourcefilename"), FROM_CLASS
            if source and f"{name}/{source}" in ours:
                add(mine, node, counters)
                add(packages.setdefault(name, {}), node, counters)
            else:
                add(theirs, node, counters)
    return mine, theirs, packages


def table(totals, floors):
    header = "| metric | covered | total | % |"
    divider = "| --- | ---: | ---: | ---: |"
    if floors:
        header += " floor |"
        divider += " ---: |"
    rows = [header, divider]
    for name in COUNTERS:
        if name in totals:
            covered, total = totals[name]
            row = f"| {name.lower()} | {covered} | {total} | {pct(covered, total)} |"
            if floors:
                floor = floors.get(name)
                row += f" {floor:g}% |" if floor is not None else " — |"
            rows.append(row)
    return rows


def breaches(totals, floors):
    """Gated counters below their floor, as ready-made messages.

    A gated counter with nothing in it fails rather than passes: no instructions to cover means
    the run measured nothing, which a gate must not read as success.
    """
    problems = []
    for name, floor in floors.items():
        covered, total = totals.get(name, (0, 0))
        if not total:
            problems.append(f"{name.lower()} coverage is missing from the report entirely")
        elif 100.0 * covered / total < floor:
            # Spell out the budget: "below 75%" is less actionable than the miss count the floor
            # allowed, which shows how far off the change is.
            allowed = int(total * (100.0 - floor) / 100.0)
            problems.append(
                f"{name.lower()} coverage is {pct(covered, total)}, below the {floor:g}% floor — "
                f"{total - covered} of {total} missed, and the floor allows {allowed}"
            )
    return problems


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    sources = DEFAULT_SOURCES
    floors = {}
    for arg in sys.argv[1:]:
        if arg.startswith("--sources="):
            sources = arg.split("=", 1)[1]
        elif arg.startswith(MIN_PREFIX):
            name, _, value = arg[len(MIN_PREFIX):].partition("=")
            metric = name.upper()
            # A typo in a floor's name would otherwise disable that gate silently, the one
            # failure mode a coverage gate must not have.
            if metric not in COUNTERS:
                sys.exit(f"{arg}: no such counter; expected one of "
                         f"{', '.join(c.lower() for c in COUNTERS)}")
            try:
                floors[metric] = float(value)
            except ValueError:
                sys.exit(f"{arg}: wants a percentage, got {value!r}")
    if not args:
        sys.exit("usage: coverage-summary.py <report.xml> [label] [--sources=DIR] "
                 "[--min-instruction=N ...]")
    path = args[0]
    label = args[1] if len(args) > 1 else os.path.basename(path)

    if not os.path.exists(path):
        sys.exit(f"no coverage report at {path}")
    if not os.path.isdir(sources):
        sys.exit(f"no source tree at {sources}; run this from the repository root")

    root = ET.parse(path).getroot()
    mine, theirs, packages = tally(root, authored(sources))

    lines = [f"### coverage — {label}", ""] + table(mine, floors)

    lines += ["", "| package | instructions | lines |", "| --- | ---: | ---: |"]
    # Biggest packages first: a 0% package of twelve instructions isn't the interesting one.
    for name in sorted(packages, key=lambda n: -packages[n].get("INSTRUCTION", (0, 0))[1]):
        counts = packages[name]
        instr = counts.get("INSTRUCTION", (0, 0))
        line = counts.get("LINE", (0, 0))
        lines.append(f"| `{name.replace('/', '.')}` | {pct(*instr)} | {pct(*line)} |")

    generated = theirs.get("INSTRUCTION", (0, 0))
    if generated[1]:
        lines += [
            "",
            f"Generated and inlined code is excluded above: {generated[1]} instructions "
            f"({pct(*generated)} covered), from Room's KSP output and library sources the "
            "compiler inlined into our packages.",
        ]

    problems = breaches(mine, floors)
    if problems:
        lines += ["", f"**Below the floor on {label}:**"] + [f"- {p}" for p in problems]

    report = "\n".join(lines)
    print(report)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(report + "\n\n")

    for problem in problems:
        print(f"::error::coverage ({label}): {problem}", file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
