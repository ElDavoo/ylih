#!/usr/bin/env python3
"""Turn a JaCoCo report.xml into a readable summary.

Printed to stdout and, when running under Actions, appended to the job summary so the number
shows on the workflow run page without opening the HTML artifact. Deliberately not a third-party
action: this needs no token, no network and no service that gets to see the repository.

Usage: coverage-summary.py <report.xml> [label]
"""

import os
import sys
import xml.etree.ElementTree as ET

# Ordered as they are worth reading: the totals people quote first, the rest for detail.
COUNTERS = ("INSTRUCTION", "LINE", "BRANCH", "METHOD", "CLASS")


def totals(node):
    out = {}
    for counter in node.findall("counter"):
        missed = int(counter.get("missed"))
        covered = int(counter.get("covered"))
        out[counter.get("type")] = (covered, missed + covered)
    return out


def pct(covered, total):
    return f"{100.0 * covered / total:.1f}%" if total else "n/a"


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: coverage-summary.py <report.xml> [label]")
    path = sys.argv[1]
    label = sys.argv[2] if len(sys.argv) > 2 else os.path.basename(path)

    if not os.path.exists(path):
        sys.exit(f"no coverage report at {path}")

    root = ET.parse(path).getroot()
    overall = totals(root)

    lines = [f"### coverage — {label}", ""]
    lines.append("| metric | covered | total | % |")
    lines.append("| --- | ---: | ---: | ---: |")
    for name in COUNTERS:
        if name in overall:
            covered, total = overall[name]
            lines.append(f"| {name.lower()} | {covered} | {total} | {pct(covered, total)} |")

    lines += ["", "| package | instructions | lines |", "| --- | ---: | ---: |"]
    packages = []
    for package in root.findall("package"):
        counts = totals(package)
        instr = counts.get("INSTRUCTION", (0, 0))
        line = counts.get("LINE", (0, 0))
        packages.append((package.get("name").replace("/", "."), instr, line))
    # Biggest packages first: a 0% package of twelve instructions is not the interesting one.
    for name, instr, line in sorted(packages, key=lambda p: -p[1][1]):
        lines.append(f"| `{name}` | {pct(*instr)} | {pct(*line)} |")

    report = "\n".join(lines)
    print(report)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(report + "\n\n")


if __name__ == "__main__":
    main()
