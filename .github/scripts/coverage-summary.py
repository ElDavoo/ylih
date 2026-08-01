#!/usr/bin/env python3
"""Turn a JaCoCo report.xml into a readable summary.

Printed to stdout and, when running under Actions, appended to the job summary so the number
shows on the workflow run page without opening the HTML artifact. Deliberately not a third-party
action: this needs no token, no network and no service that gets to see the repository.

The report covers more than this repository wrote. Room's KSP output (`*Dao_Impl`, roughly a
fifth of the instructions) lands in it, and so do the stdlib and coroutines sources that get
inlined into our classes and attributed to our packages — `SafeCollector.common.kt`, `Emitters.kt`,
`LazyDsl.kt`, `Comparisons.kt`. None of that is code anyone here can write a test *for*, and it
answers to whoever generated it, so the headline number is taken over the classes whose source
file actually exists under the source tree. What was dropped is printed underneath rather than
quietly left out; AGP's coverage task has no exclusion setting, so the full report on disk still
has everything.

`--min-<metric>=N` turns the summary into a gate: the run fails if that counter, over the authored
code, falls below N percent. CI sets instruction 95, line 99 and branch 75, each a little under
where the suite actually sits, so the gate catches a regression rather than tracking noise.

Branch is the one to understand before touching these numbers. It sits in the seventies rather
than the nineties because the Compose compiler emits a `$changed`/default-argument bitmask branch
for every composable parameter and no test drives those — the Compose-heavy files hold about two
thirds of the missed branches. So 75 is not a weak version of 95; it is a different measurement,
and raising it would mean writing tests for compiler-generated dispatch. It is also the tightest
floor of the three: around ten missed branches of headroom, which one new composable with enough
parameters can spend without any real regression. If it fails, check what it is failing *on*
before adding tests to appease it.

Method and class are left ungated deliberately. At ~95% they sit six classes above a 90 floor,
close enough that ordinary work trips them without coverage having actually regressed.

Usage: coverage-summary.py <report.xml> [label] [--sources=DIR] [--min-instruction=N ...]
"""

import os
import sys
import xml.etree.ElementTree as ET

# Ordered as they are worth reading: the totals people quote first, the rest for detail.
COUNTERS = ("INSTRUCTION", "LINE", "BRANCH", "METHOD", "CLASS")

DEFAULT_SOURCES = "app/src"

# Floors arrive as --min-<metric>=N, so the set that is gated is whatever CI passes rather than
# something baked in here. See the module docstring for what the numbers mean.
MIN_PREFIX = "--min-"

# A line belongs to its source file, not to each of the classes compiled out of it; taking LINE
# off the classes would count a file once per Compose lambda it contains.
FROM_CLASS = ("INSTRUCTION", "BRANCH", "METHOD", "CLASS")
FROM_SOURCEFILE = ("LINE",)

MARKER = os.sep + "java" + os.sep


def authored(sources):
    """Every `<package>/<file.kt>` with a file behind it, across all source sets.

    Keyed the way JaCoCo names things — its `package` is a slash-separated path and its
    `sourcefilename` is a bare file name — so a lookup needs no guessing at variant directories.
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
    """Totals over authored code, the same over everything else, and the per-package split.

    Instructions and branches come off the classes, because that is the granularity a generated
    class can be told apart at. Lines come off the source files instead: JaCoCo counts a line
    once however many classes were compiled out of it, and summing the classes would count the
    lines of every Compose lambda again for each one.
    """
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
    """Gated counters that fall below their floor, as ready-made messages.

    A gated counter with nothing in it fails rather than passes: a report with no instructions to
    cover means the run measured nothing, and a gate must not read that as success.
    """
    problems = []
    for name, floor in floors.items():
        covered, total = totals.get(name, (0, 0))
        if not total:
            problems.append(f"{name.lower()} coverage is missing from the report entirely")
        elif 100.0 * covered / total < floor:
            # Spell out the budget: "below 75%" is far less actionable than the count of misses
            # that would have been allowed, which is what tells you how far off the change is.
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
            # A typo in a floor's name would otherwise disable that gate silently, which is the
            # one failure mode a coverage gate must not have.
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
    # Biggest packages first: a 0% package of twelve instructions is not the interesting one.
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
