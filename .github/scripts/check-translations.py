#!/usr/bin/env python3
"""Validate translations against the English base.

Lint enforces that every string exists in every locale; it cannot see whether the
text was ever translated, nor whether a format specifier survived the round trip.
This checks both, plus the two typography rules lint does enforce, so a translation
edit can be verified without a 90-second Gradle run.

Usage:  check-translations.py [values-xx ...]     (default: every locale)
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
RES = os.path.join(REPO, "app/src/main/res")

# Latin technical tokens the house style deliberately leaves as-is, so being
# identical to English is not evidence they were skipped.
LOANWORDS = {"stats_pair_row", "kind_usb", "kind_bluetooth", "kind_ble", "devices_recent"}

FMT = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]")


def load(path):
    root = ET.parse(path).getroot()
    strings, plurals = {}, {}
    for e in root:
        if e.tag == "string":
            if e.get("translatable") == "false":
                continue
            strings[e.get("name")] = "".join(e.itertext())
        elif e.tag == "plurals":
            plurals[e.get("name")] = {i.get("quantity"): "".join(i.itertext()) for i in e}
    return strings, plurals


def specs(text):
    return sorted(m.group(0) for m in FMT.finditer(text.replace("%%", "")))


def committed_quantities(folder):
    """The plural quantity set as last committed.

    The CLDR categories a language takes are not ours to choose — lint (MissingQuantity) already
    holds each file to them. What an edit can do is quietly drop one while rewording, so the
    useful question is whether this edit changed the set, which HEAD answers without a snapshot
    file to keep in step.
    """
    path = f"app/src/main/res/{folder}/strings.xml"
    show = subprocess.run(["git", "show", f"HEAD:{path}"],
                          cwd=REPO, capture_output=True, text=True)
    if show.returncode != 0:
        return {}
    try:
        root = ET.fromstring(show.stdout)
    except ET.ParseError:
        return {}
    return {e.get("name"): sorted(i.get("quantity") for i in e)
            for e in root if e.tag == "plurals"}


def locales():
    skip = {"values-night", "values-v31", "values-night-v31"}
    return sorted(
        d for d in os.listdir(RES)
        if d.startswith("values-") and d not in skip
        and os.path.exists(os.path.join(RES, d, "strings.xml"))
    )


def check(folder, base_s, base_p):
    path = os.path.join(RES, folder, "strings.xml")
    errors, warnings = [], []
    try:
        s, p = load(path)
    except ET.ParseError as e:
        return [f"XML does not parse: {e}"], []

    missing = sorted(set(base_s) - set(s))
    extra = sorted(set(s) - set(base_s))
    if missing:
        errors.append(f"missing {len(missing)} strings: {missing[:8]}")
    if extra:
        errors.append(f"unknown strings: {extra[:8]}")
    if sorted(p) != sorted(base_p):
        errors.append(f"plurals differ: has {sorted(p)}, want {sorted(base_p)}")

    for name, qs in committed_quantities(folder).items():
        if name in p and sorted(p[name]) != qs:
            errors.append(f"{name}: plural quantities changed {qs} -> {sorted(p[name])}")

    for k in base_s:
        if k in s and specs(s[k]) != specs(base_s[k]):
            errors.append(f"{k}: format specifiers {specs(base_s[k])} -> {specs(s[k])}")
    for k in base_p:
        want_spec = specs(base_p[k].get("other", ""))
        for q, txt in p.get(k, {}).items():
            if specs(txt) != want_spec:
                errors.append(f"{k}[{q}]: format specifiers {want_spec} -> {specs(txt)}")

    # Only the values, never the whole file: lint reads string content, so an apostrophe inside
    # an XML comment ("Types d'appareil") is not a finding and must not be reported as one.
    values = list(s.values()) + [t for q in p.values() for t in q.values()]
    quotes = sum(v.count("'") for v in values)
    if quotes:
        errors.append(f"{quotes} ASCII apostrophe(s) — lint TypographyQuotes; use the letter this "
                      f"language actually takes (’ U+2019, or ʻ/ʼ where it is a consonant)")
    ellipses = sum(v.count("...") for v in values)
    if ellipses:
        errors.append(f"{ellipses} literal '...' — lint TypographyEllipsis; use …")

    english = sorted(k for k in base_s
                     if k not in LOANWORDS and k in s and s[k].strip() == base_s[k].strip())
    if english:
        warnings.append(f"{len(english)}/{len(base_s) - len(LOANWORDS)} strings still English: "
                        f"{english[:6]}")
    return errors, warnings


def main():
    base_s, base_p = load(os.path.join(RES, "values/strings.xml"))
    targets = sys.argv[1:] or locales()

    bad = 0
    for folder in targets:
        errors, warnings = check(folder, base_s, base_p)
        if errors or warnings:
            print(f"{folder}:")
            for e in errors:
                print(f"  ERROR {e}")
            for w in warnings:
                print(f"  WARN  {w}")
        if errors:
            bad += 1
    print(f"\n{len(targets)} locale(s) checked, {bad} with errors")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
