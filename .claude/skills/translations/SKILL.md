---
name: translations
description: Add, edit, rename, remove or review a user-visible string across all 76 translated locales at once, create a new values-<lang> translation file, or validate every strings.xml before running lint. Use for any change to app/src/main/res/values*/strings.xml — localization, translation, i18n, new language, MissingTranslation, MissingQuantity.
---

# Bulk-editing strings.xml

Every user-visible string in this app lives in `app/src/main/res/values/strings.xml` and is
mirrored into **76 translation folders**, heading for 200+. Editing them one `Edit` call at a time
does not scale and is how the build has actually broken before.

**Use the driver. One JSON job, one command, all 77 files.**

```sh
node .claude/skills/translations/strings.mjs <command>
```

Paths below are relative to the repo root. No dependencies — plain Node (v24 here), no `nix
develop` needed. Plural categories come from `Intl.PluralRules`, so the CLDR table is ICU's and
cannot drift.

## Always run this before lint

```sh
node .claude/skills/translations/strings.mjs check
```

0.14s, versus 69s for `lintClassicDebug`. Exit 1 and a line per problem. It catches every way this
tree's string resources have broken the build:

| Reported as | Real failure |
|---|---|
| `MissingTranslation` | key in `values/` absent from a locale — lint error |
| `MissingQuantity` | `<plurals>` missing a CLDR category — lint error |
| `unescaped apostrophe` | aapt2 rejects it, and AGP reports it as a bare `java.lang.NullPointerException` from `ResourceCompilerRunnable` naming **no file** |
| `placeholders %2$s != source %1$s` | `StringFormatMatches` — a renumbered arg is a runtime crash |
| `UnusedResources` | a new string nothing references — **lint error in this project** |
| `folder values-xx/ has no strings.xml` | an *empty* locale folder. Invisible to `git status`; lint reads folder names and reports every string missing for it. Cost 113 errors once |
| `script: the file is written in X` | a locale written in the wrong script — romanised Sanskrit, Kannada under `values-b+kxv+Latn`. Every other check passes: the strings are present, translated and unreadable to the people who asked for that language. The expected script comes from `Intl.Locale().maximize()`, so the table cannot drift from CLDR |
| `near-duplicate locales` | one bulk run copied into two folders and then edited. Not byte-identical, so the duplicate check below cannot see it; `sat` held Samburu, 99% of its words shared with `saq` |

Pass `--no-usage` to skip the reference scan. It reads every file under `app/src/main/java` for
`R.string.<name>`, and `AndroidManifest.xml` plus the non-`values` resource XML for `@string/<name>`
— the widget picker labels are named only from `android:label` and `res/xml/widget_*_info.xml`, and
lint counts those as uses.

## Add or edit strings across every locale

Write **one** job file, apply it once. Keys already present are updated in place; keys absent are
inserted. Both are idempotent, so re-running a partly-applied job is safe.

```sh
cat > /tmp/job.json <<'EOF'
{
  "after": "session_recovered",
  "source": { "session_shortest": "shortest", "session_gap": "gap of %1$s" },
  "translations": {
    "it": { "session_shortest": "più breve", "session_gap": "pausa di %1$s" },
    "de": { "session_shortest": "kürzeste", "session_gap": "pause von %1$s" },
    "iw": { "session_shortest": "הקצר ביותר", "session_gap": "פער של %1$s" },
    "ff-Latn": { "session_shortest": "seedd'ere", "session_gap": "gap %1$s" }
  }
}
EOF
node .claude/skills/translations/strings.mjs apply /tmp/job.json --fill-missing
```

```
applied to 77 files
values	UnusedResources: no R.string/R.plurals reference for session_shortest,session_gap
FAIL 1 problems across 76 locales
```

`apply` runs `check` for you when it finishes. That `FAIL` is the expected and correct result here:
the strings exist in all 77 files but no Kotlin file uses them yet. Add the `stringResource(...)`
call site, re-run `check`, and it goes green — see the first gotcha.

- `after` — the existing key to insert new keys after. Several new keys keep the order you wrote
  them. Omit and they land before `</resources>`.
- `source` — also writes `values/strings.xml`. Omit for a translation-only fix.
- `--fill-missing` — locales you did not list get the English text, so the build stays green while
  translation catches up. **Without it, unlisted locales are left alone** and `check` will report
  `MissingTranslation` for a new key — which is the right default when you intend to translate
  properly before committing.
- Plurals are objects: `"session_count": { "one": "…", "other": "…" }`. Categories absent from your
  object are filled from `other` and categories the locale does not need are dropped, so you cannot
  emit a `MissingQuantity`.
- **Never escape anything yourself.** The driver escapes `'`, `&`, `<`, `>` on write. Write
  `seedd'ere`, get `seedd\'ere`. Pre-escaping double-escapes.

`remove` and `rename` take the same route and also hit all 77 files:

```sh
printf '{"remove":["session_shortest","session_gap"]}' > /tmp/rm.json
node .claude/skills/translations/strings.mjs apply /tmp/rm.json
printf '{"rename":{"stats_share":"stats_export"}}' > /tmp/mv.json
node .claude/skills/translations/strings.mjs apply /tmp/mv.json
```

`rename` touches resource files only — update the `R.string.*` call sites in `app/src/main/java`
yourself, or the `UnusedResources` line in `check`'s output will tell you that you didn't.

## Get the job skeleton for a translator

```sh
node .claude/skills/translations/strings.mjs template --keys nav_stats --locales it,de,ja
```

```json
{
  "locales": {
    "it": "Italian — plurals: one,many,other",
    "de": "German — plurals: one,other",
    "ja": "Japanese — plurals: other"
  },
  "keys": {
    "nav_stats": { "en": "stats", "placeholders": [] }
  },
  "translations": {}
}
```

Omit `--keys` for all 113, omit `--locales` for all 76, `-o file.json` to write it. A translating
agent fills `translations` and hands the file to `apply` — **one file written, not 77 edits.** For
a large campaign, slice with `--locales` and give each agent its own slice.

## Review one string everywhere

```sh
node .claude/skills/translations/strings.mjs get nav_stats
```

```
en         stats
af         statistieke
agq        tatistik
...
zh-TW      統計
```

## Add a new language

```sh
node .claude/skills/translations/strings.mjs new-locale blo /tmp/job.json
```

```
wrote app/src/main/res/values-b+blo/strings.xml (113 keys, plurals: zero,one,other)
WARNING 111 keys left in english: app_title,kind_bluetooth,kind_ble,…
```

Takes a BCP 47 tag and picks the folder shape for you — this is the part that is easy to get
silently wrong:

| Tag | Folder | Why |
|---|---|---|
| `cv` | `values-cv` | 2-letter, legacy qualifier works |
| `pt-BR` | `values-pt-rBR` | legacy region form |
| `blo` | `values-b+blo` | 3-letter codes **need** the BCP 47 form |
| `az-Cyrl` | `values-b+az+Cyrl` | script subtags **need** the BCP 47 form |
| `he` | `values-**iw**` | Android froze the pre-1989 code |
| `id` | `values-**in**` | same; `yi` → `ji` |

`values-b+…` is only legal because `minSdk = 26`. A `values-he/` folder is never matched at
runtime — it fails silently, which is worse than a build error, so the driver rewrites it.

The file is written with the source's blank-line grouping, the standard header comment, and each
plural expanded to that locale's categories. Untranslated keys keep the English text and are
counted in the `WARNING`. **Don't leave a locale in that state**: it makes the app advertise a
language it then shows in English. `res/resources.properties` and `generateLocaleConfig` pick the
new folder up with no further wiring.

## Normalise plurals in bulk

```sh
node .claude/skills/translations/strings.mjs fix-plurals
```

Re-renders every `<plurals>` to exactly its locale's ICU categories, filling absent ones from
`other`. Fixes `MissingQuantity` across the tree without touching a word of translated text.

## Inspect the locale set

```sh
node .claude/skills/translations/strings.mjs locales
```

```
af         values-af              113 keys  plurals: one,other
agq        values-b+agq           113 keys  plurals: one,many,other
ar         values-ar              113 keys  plurals: zero,one,two,few,many,other
...
76 locales
```

## Gotchas

- **`UnusedResources` is an error here, not a warning.** Adding a string that nothing references —
  no `R.string.<name>` in `app/src/main/java`, no `@string/<name>` in the manifest or a resource
  XML — fails `lintClassicDebug`. Adding copy and adding its call site are one change. `check`
  catches this; lint takes 69s to say the same thing.
- **ICU is a *superset* of lint's plural table.** CLDR gives `fr`, `ca`, `pt-BR` and `it` a `many`
  category (multiples of a million); Android lint does not require it. The driver emits the ICU set
  deliberately — the extra category is only an `UnusedQuantity` **warning** and
  `warningsAsErrors = false`, whereas guessing short breaks the build. Verified in both directions:
  lint also accepts `fix-plurals` *removing* `many` from `iw` and `one` from `bm`/`bo`/`dz`.
- **The script check is per locale, not per string.** It reads the letters of the whole file and
  compares the dominant script with the one CLDR expects for the tag, so a few Latin tokens —
  `bluetooth`, `usb`, the app's own name — do not trip it, and a wholly romanised file cannot hide.
  Fixing one is usually mechanical: when the text really is the language, only in the wrong
  alphabet, transliterate it and check the result against words CLDR already writes in both scripts
  (its month and weekday names, and its relative-day fields). That is how `values-b+az+Cyrl`,
  `values-b+ff+Adlm`, `values-b+zgh` and `values-b+csw` were converted — and how the converters were
  proved right before anything was written.
- **`values-night/` is not a locale.** Android's short qualifier keywords (`car`, `tv`, `land`) are
  shaped exactly like language codes; the driver filters on a denylist plus the presence of
  `strings.xml`. Don't hand-roll a `ls values-*` loop.
- **The driver is line-based, not DOM-based, on purpose.** A real XML round-trip would reflow all 77
  files and bury a one-string change in a 10,000-line diff. Verified reversible: `apply` an add then
  `apply` the matching `remove` leaves every file byte-identical. It relies on each `<string>` being
  on one line, which is true throughout this tree — if you ever wrap one, the driver won't see it.
- **`tools:ignore` in a translation is legitimate.** `values-nb` suppresses `Typos` on "internett".
  `apply` preserves existing attributes when it updates a value. `check` only objects if `tools:` is
  used without an `xmlns:tools` declaration.
- **House style is lowercase throughout, deliberately** — including in translations, and including
  where the language's typography would capitalise a sentence start. `app_name` and `value_none`
  carry `translatable="false"` and must be **absent** from every translation; `template` and
  `new-locale` already exclude them.

## Troubleshooting

- **`mergeClassicDebugResources` fails with `java.lang.NullPointerException` and no file named** —
  an unescaped apostrophe. `--stacktrace` tells you nothing. Run `check`, or find it directly:
  ```sh
  $ANDROID_HOME/build-tools/37.0.0/aapt2 compile -o /tmp/out app/src/main/res/values-xx/strings.xml
  ```
- **113 `MissingTranslation` errors for a language you never added** — an empty `values-<lang>/`
  folder. `git status` cannot see it. `check` reports it.
- **`check` says `unknown keys`** — the locale has a key `values/strings.xml` doesn't. Usually a
  typo'd name from a hand edit; `apply` with `remove` or `rename`.
- **Import the driver instead of shelling out** (`locales`, `categories`, `tagToDir`, `esc`, `parse`
  are exported) for a one-off the commands don't cover:
  ```sh
  node --input-type=module -e '
  const {tagToDir,categories}=await import("/home/dave/git/ylih/.claude/skills/translations/strings.mjs");
  console.log(tagToDir("sr-Latn"), categories("cy").join(","))'
  ```
- **Test the driver without risking the tree** — point it at a copy:
  ```sh
  YLIH_RES=/tmp/res node .claude/skills/translations/strings.mjs check
  ```
