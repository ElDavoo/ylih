#!/usr/bin/env node
// Bulk editor for app/src/main/res/values*/strings.xml.
//
// Exists because the alternative is 77 (soon 200+) individual Edit calls, which is neither
// scalable nor safe: every one of the three ways this repo's build has actually broken —
// unescaped apostrophes, a plural missing a CLDR category, a key present in values/ but absent
// from one locale — is a mechanical mistake that a per-file hand edit invites and a single pass
// over every file can rule out by construction.
//
// Deliberately line-based rather than DOM-based: a real XML round-trip would reflow all 77 files
// and bury one string change in a 10,000-line diff. Every write here touches only the lines that
// belong to the key being changed.

import { readFileSync, writeFileSync, existsSync, readdirSync, mkdirSync } from 'node:fs'
import { basename, join } from 'node:path'

const RES = process.env.YLIH_RES ?? 'app/src/main/res'
const IND = '    '

// ---------------------------------------------------------------------------
// locale folders
// ---------------------------------------------------------------------------

// values-night is a UI-mode qualifier, not a locale, and Android's short qualifier keywords
// (car, tv, land…) are shaped exactly like language codes. Requiring strings.xml is what actually
// separates them in practice; the denylist covers the case where someone adds values-tv/strings.xml.
const NOT_LOCALES = new Set([
  'night', 'notnight', 'land', 'port', 'car', 'tv', 'watch', 'desk', 'appliance', 'vrheadset',
  'ldpi', 'mdpi', 'hdpi', 'xhdpi', 'nodpi', 'tvdpi', 'anydpi', 'ldrtl', 'ldltr', 'round', 'notround',
])

/** `values-b+az+Cyrl` -> `az-Cyrl`, `values-pt-rBR` -> `pt-BR`. */
export function dirToTag(dir) {
  const q = dir.replace(/^values-?/, '')
  if (!q) return unqualifiedLocale()
  if (q.startsWith('b+')) return q.slice(2).replace(/\+/g, '-')
  return q.replace(/-r([A-Z]{2})$/, '-$1')
}

// Android froze these at their pre-1989 ISO 639 codes. A values-he/ or values-id/ folder is never
// matched at runtime — it silently does nothing, which is far worse than a build error.
const LEGACY = { he: 'iw', id: 'in', yi: 'ji' }

/** `az-Cyrl` -> `values-b+az+Cyrl`. 3-letter languages and script subtags need the BCP 47 form. */
export function tagToDir(tag) {
  const parts = tag.split(/[-_]/)
  const lang = LEGACY[parts[0].toLowerCase()] ?? parts[0].toLowerCase()
  const rest = parts.slice(1)
  const legacy = lang.length <= 2 && (rest.length === 0 || (rest.length === 1 && /^[A-Za-z]{2}$/.test(rest[0])))
  if (legacy) return rest.length ? `values-${lang}-r${rest[0].toUpperCase()}` : `values-${lang}`
  const norm = rest.map((p) => (p.length === 4 ? p[0].toUpperCase() + p.slice(1).toLowerCase() : p.toUpperCase()))
  return `values-b+${[lang, ...norm].join('+')}`
}

function unqualifiedLocale() {
  const p = join(RES, 'resources.properties')
  const m = existsSync(p) && readFileSync(p, 'utf8').match(/^unqualifiedResLocale\s*=\s*(\S+)/m)
  return m ? m[1] : 'en'
}

/** Every translation folder, source excluded, sorted by tag. */
export function locales() {
  return readdirSync(RES)
    .filter((d) => /^values-(b\+[A-Za-z+]+|[a-z]{2,3}(-r[A-Z]{2})?)$/.test(d))
    .filter((d) => !NOT_LOCALES.has(d.slice(7)))
    .filter((d) => existsSync(join(RES, d, 'strings.xml')))
    .map((dir) => ({ dir, tag: dirToTag(dir), path: join(RES, dir, 'strings.xml') }))
    .sort((a, b) => a.tag.localeCompare(b.tag))
}

/** The CLDR plural categories this locale needs. Authority is ICU, not a table we maintain. */
export function categories(tag) {
  try {
    return new Intl.PluralRules(tag).resolvedOptions().pluralCategories
  } catch {
    return ['other']
  }
}

const englishName = (tag) => {
  try {
    return new Intl.DisplayNames(['en'], { type: 'language' }).of(tag) ?? tag
  } catch {
    return tag
  }
}

// ---------------------------------------------------------------------------
// parse / escape
// ---------------------------------------------------------------------------

const STR = /^(\s*)<string\s+name="([^"]+)"([^>]*)>(.*)<\/string>\s*$/
const PL_OPEN = /^(\s*)<plurals\s+name="([^"]+)"/
const ITEM = /^\s*<item\s+quantity="([a-z]+)"\s*>(.*)<\/item>\s*$/

/**
 * Index a strings.xml by key. Returns {keys: Map<name, entry>, lines}, where an entry carries the
 * line span it occupies so edits can be surgical.
 */
export function parse(path) {
  return index(readFileSync(path, 'utf8').split('\n'), path)
}

/** Same, over lines already in memory — used to re-index after each splice. */
function index(lines, path) {
  const keys = new Map()
  for (let i = 0; i < lines.length; i++) {
    const s = lines[i].match(STR)
    if (s) {
      keys.set(s[2], { kind: 'string', name: s[2], from: i, to: i, attrs: s[3], value: s[4] })
      continue
    }
    const p = lines[i].match(PL_OPEN)
    if (p) {
      const items = new Map()
      let j = i + 1
      for (; j < lines.length && !/<\/plurals>/.test(lines[j]); j++) {
        const it = lines[j].match(ITEM)
        if (it) items.set(it[1], it[2])
      }
      keys.set(p[2], { kind: 'plurals', name: p[2], from: i, to: j, items })
      i = j
    }
  }
  return { keys, lines, path }
}

/**
 * Escape a value for a string resource. aapt2 rejects a bare apostrophe, and AGP reports that as
 * `java.lang.NullPointerException` from ResourceCompilerRunnable with no file named — the single
 * most expensive minute-per-character bug in this tree. Never write a value without this.
 */
export function esc(text) {
  return String(text)
    .replace(/&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/(?<!\\)'/g, "\\'")
}

const placeholders = (t) => (String(t).match(/%(?:\d+\$)?[a-zA-Z]/g) ?? []).sort()

// ---------------------------------------------------------------------------
// source of truth
// ---------------------------------------------------------------------------

export function source() {
  const src = parse(join(RES, 'values', 'strings.xml'))
  const translatable = new Map()
  for (const [name, e] of src.keys) {
    if (e.kind === 'string' && /translatable="false"/.test(e.attrs)) continue
    translatable.set(name, e)
  }
  return { ...src, translatable }
}

// ---------------------------------------------------------------------------
// render
// ---------------------------------------------------------------------------

function renderString(name, value, attrs = '') {
  return `${IND}<string name="${name}"${attrs}>${esc(value)}</string>`
}

/**
 * Render a plurals block covering exactly the categories `tag` requires. Missing categories fall
 * back to `other`, which is right for the many languages whose noun does not inflect after a
 * numeral and is at worst grammatically flat elsewhere — and is always better than the build
 * failing on MissingQuantity.
 */
function renderPlurals(name, byCat, tag) {
  const cats = categories(tag)
  const fallback = byCat.other ?? Object.values(byCat)[0] ?? ''
  const items = cats.map((c) => `${IND}${IND}<item quantity="${c}">${esc(byCat[c] ?? fallback)}</item>`)
  return [`${IND}<plurals name="${name}">`, ...items, `${IND}</plurals>`].join('\n')
}

// ---------------------------------------------------------------------------
// mutations — each rewrites only the lines belonging to the key
// ---------------------------------------------------------------------------

function writeLines(path, lines) {
  writeFileSync(path, lines.join('\n'))
}

function insertAfter(f, anchor, block) {
  const a = anchor && f.keys.get(anchor)
  const at = a ? a.to + 1 : f.lines.findLastIndex((l) => /<\/resources>/.test(l))
  f.lines.splice(at, 0, ...block.split('\n'))
  return f
}

function replaceSpan(f, entry, block) {
  f.lines.splice(entry.from, entry.to - entry.from + 1, ...block.split('\n'))
  return f
}

// ---------------------------------------------------------------------------
// commands
// ---------------------------------------------------------------------------

const cmds = {}

cmds.locales = () => {
  const all = locales()
  for (const l of all) {
    const n = parse(l.path).keys.size
    console.log(`${l.tag.padEnd(10)} ${l.dir.padEnd(22)} ${String(n).padStart(3)} keys  plurals: ${categories(l.tag).join(',')}`)
  }
  console.log(`\n${all.length} locales`)
}

/** Print one key across every locale, so a translation can be reviewed without opening 77 files. */
cmds.get = ([name]) => {
  if (!name) die('usage: get <key>')
  const s = source()
  const e = s.keys.get(name)
  console.log(`en${' '.repeat(8)} ${e ? fmt(e) : '(absent from source)'}`)
  for (const l of locales()) {
    const t = parse(l.path).keys.get(name)
    console.log(`${l.tag.padEnd(10)} ${t ? fmt(t) : '— MISSING'}`)
  }
}
const fmt = (e) => (e.kind === 'string' ? e.value : [...e.items].map(([q, v]) => `${q}=${v}`).join(' | '))

/**
 * Emit a job file for a translator: every translatable key with its English text, placeholders and
 * plural shape. An agent fills in `translations` per locale and hands it back to `apply`.
 */
cmds.template = (argv) => {
  const only = flag(argv, '--keys')?.split(',')
  const s = source()
  const keys = {}
  for (const [name, e] of s.translatable) {
    if (only && !only.includes(name)) continue
    keys[name] = e.kind === 'string'
      ? { en: e.value, placeholders: placeholders(e.value) }
      : { en: Object.fromEntries(e.items), placeholders: placeholders(e.items.get('other') ?? '') }
  }
  // Kept compact on purpose: this file is agent context, and 76 locales pretty-printed as objects
  // costs several hundred lines that say nothing.
  const tags = flag(argv, '--locales')?.split(',') ?? locales().map((l) => l.tag)
  const body = JSON.stringify({ keys }, null, 2).replace(/^\{\n|\n\}$/g, '')
  const list = tags.map((t) => `    "${t}": "${englishName(t)} — plurals: ${categories(t).join(',')}"`)
  out(argv, `{\n  "locales": {\n${list.join(',\n')}\n  },\n${body},\n  "translations": {}\n}\n`)
}

/**
 * Apply one JSON job to every file in a single pass. The job is the unit of work — an agent writes
 * one file, not 77 edits.
 *
 * {
 *   "after": "session_recovered",            // optional: insert new keys after this key
 *   "source": { "key": "english" },          // add/update values/strings.xml too
 *   "remove": ["dead_key"],
 *   "rename": { "old": "new" },
 *   "translations": {
 *     "it": { "key": "italiano", "some_plural": { "one": "…", "other": "…" } },
 *     "de": { "key": "deutsch" }
 *   }
 * }
 *
 * Keys already present are updated in place; keys absent are inserted. Both are idempotent, so a
 * partially-applied job can simply be re-run.
 */
cmds.apply = (argv) => {
  const job = JSON.parse(readFileSync(argv[0] ?? die('usage: apply <job.json>'), 'utf8'))
  const fillMissing = argv.includes('--fill-missing')
  const targets = [{ tag: 'en', path: join(RES, 'values', 'strings.xml'), source: true }, ...locales()]
  let touched = 0

  for (const t of targets) {
    let f = parse(t.path)
    let changed = false

    for (const [oldName, newName] of Object.entries(job.rename ?? {})) {
      const e = f.keys.get(oldName)
      if (!e) continue
      for (let i = e.from; i <= e.to; i++) f.lines[i] = f.lines[i].replace(`name="${oldName}"`, `name="${newName}"`)
      f = reindex(f)
      changed = true
    }

    for (const name of job.remove ?? []) {
      const e = f.keys.get(name)
      if (!e) continue
      f.lines.splice(e.from, e.to - e.from + 1)
      f = reindex(f)
      changed = true
    }

    // The source file gets `source`; a locale gets its own translations, falling back to the
    // english text only when --fill-missing says an untranslated-but-green build is acceptable.
    const values = t.source
      ? job.source ?? {}
      : { ...(fillMissing ? job.source ?? {} : {}), ...(job.translations?.[t.tag] ?? {}) }

    // `anchor` walks forward as keys are inserted, so a job listing several new keys lays them down
    // in the order it wrote them rather than reversing them at a fixed insertion point.
    let anchor = job.after
    for (const [name, value] of Object.entries(values)) {
      const e = f.keys.get(name)
      const block = typeof value === 'object' && value !== null
        ? renderPlurals(name, value, t.tag)
        : renderString(name, value, e?.attrs ?? '')
      if (e) replaceSpan(f, e, block)
      else { insertAfter(f, anchor, block); anchor = name }
      f = reindex(f)
      changed = true
    }

    if (changed) {
      writeLines(t.path, f.lines)
      touched++
    }
  }
  console.log(`applied to ${touched} files`)
  cmds.check(argv) // so --no-usage / --no-exit reach the check that apply runs for you
}

// Re-index after every splice. Line offsets go stale the moment a block of a different height is
// substituted, and at this file size a reparse costs nothing next to that class of bug.
const reindex = (f) => index(f.lines, f.path)

/** Create a whole new locale file from a job, with the right plural categories and header. */
cmds['new-locale'] = (argv) => {
  const tag = argv[0] ?? die('usage: new-locale <bcp47-tag> [job.json]')
  const job = argv[1] && !argv[1].startsWith('--') ? JSON.parse(readFileSync(argv[1], 'utf8')) : {}
  const given = job.translations?.[tag] ?? job[tag] ?? {}
  const dir = tagToDir(tag)
  const path = join(RES, dir, 'strings.xml')
  if (existsSync(path) && !argv.includes('--force')) die(`${path} exists (pass --force)`)

  const s = source()
  const byLine = new Map([...s.keys.values()].map((e) => [e.from, e]))
  const body = []
  // Walk the source in order, carrying its blank-line grouping across. Comments are dropped rather
  // than copied: an untranslated English section header in a translated file reads as an oversight,
  // and the blank lines alone preserve the shape that makes the file scannable.
  for (let i = 0; i < s.lines.length; i++) {
    const e = byLine.get(i)
    if (!e) {
      if (/^\s*$/.test(s.lines[i]) && body.length && body.at(-1) !== '') body.push('')
      continue
    }
    i = e.to
    if (!s.translatable.has(e.name)) continue
    const v = given[e.name]
    body.push(e.kind === 'plurals'
      ? renderPlurals(e.name, typeof v === 'object' && v ? v : Object.fromEntries(e.items), tag)
      : renderString(e.name, v ?? e.value, ''))
  }
  while (body.at(-1) === '') body.pop()

  const missing = [...s.translatable.keys()].filter((k) => !(k in given))
  const header = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<!--',
    `  ${englishName(tag)}. Same house style as the default locale: lowercase throughout, on purpose.`,
    '',
    '  `app_name` is deliberately absent — the launcher label is a name, not a word, and does not',
    '  translate. So is `value_none`, which is an em dash.',
    '-->',
    '<resources>',
  ]
  mkdirSync(join(RES, dir), { recursive: true })
  writeFileSync(path, [...header, ...body, '</resources>', ''].join('\n'))
  const n = body.filter((l) => l !== '').length // body carries the source's blank lines too
  console.log(`wrote ${path} (${n} keys, plurals: ${categories(tag).join(',')})`)
  if (missing.length) console.log(`WARNING ${missing.length} keys left in english: ${missing.slice(0, 8).join(',')}${missing.length > 8 ? '…' : ''}`)
}

/**
 * Re-render every <plurals> to exactly the categories its locale needs, filling any absent one from
 * `other`. Fixes MissingQuantity in bulk without touching a word of translated text.
 *
 * ICU is a superset of what lint enforces (CLDR gives fr/ca/pt a `many` for multiples of a million;
 * lint's table does not), so this can add a category lint calls UnusedQuantity — a warning, and
 * warningsAsErrors is off. Erring on the superset side is deliberate: the failure mode in the other
 * direction is a broken build.
 */
cmds['fix-plurals'] = () => {
  let n = 0
  for (const l of locales()) {
    let f = parse(l.path)
    let changed = false
    for (const name of [...f.keys.keys()]) {
      const e = f.keys.get(name)
      if (e.kind !== 'plurals') continue
      const block = renderPlurals(name, Object.fromEntries(e.items), l.tag)
      if (block === f.lines.slice(e.from, e.to + 1).join('\n')) continue
      replaceSpan(f, e, block)
      f = reindex(f)
      changed = true
    }
    if (changed) { writeLines(l.path, f.lines); console.log(`${l.tag}: ${categories(l.tag).join(',')}`); n++ }
  }
  console.log(`normalised ${n} files`)
}

/**
 * Everything the build can reject, checked in ~200ms instead of a two-minute lint run. Run this
 * after every bulk edit; a clean result here has matched `lint` on every failure seen so far.
 */

/**
 * The script a locale is supposed to be written in, asked of CLDR rather than listed by hand:
 * Intl.Locale().maximize() reads the same likely-subtags data Android resolves a locale with.
 */
const SCRIPT_NAMES = {
  Latn: 'Latin', Cyrl: 'Cyrillic', Deva: 'Devanagari', Arab: 'Arabic', Beng: 'Bengali',
  Guru: 'Gurmukhi', Gujr: 'Gujarati', Orya: 'Oriya', Taml: 'Tamil', Telu: 'Telugu',
  Knda: 'Kannada', Mlym: 'Malayalam', Sinh: 'Sinhala', Thai: 'Thai', Laoo: 'Lao',
  Mymr: 'Myanmar', Khmr: 'Khmer', Hans: 'Han', Hant: 'Han', Jpan: 'Han', Kore: 'Hangul',
  Ethi: 'Ethiopic', Armn: 'Armenian', Geor: 'Georgian', Hebr: 'Hebrew', Tibt: 'Tibetan',
  Cher: 'Cherokee', Cans: 'Canadian_Aboriginal', Adlm: 'Adlam', Nkoo: 'Nko', Olck: 'Ol_Chiki',
  Syrc: 'Syriac', Tfng: 'Tifinagh', Thaa: 'Thaana', Vaii: 'Vai', Mtei: 'Meetei_Mayek',
  Yiii: 'Yi', Osge: 'Osage', Grek: 'Greek',
}
const SCRIPT_TESTS = Object.entries(SCRIPT_NAMES)
  .map(([code, name]) => [name, new RegExp('\\p{Script=' + name + '}', 'u')])

export function expectedScript(tag) {
  try {
    return SCRIPT_NAMES[new Intl.Locale(tag).maximize().script] ?? null
  } catch {
    return null
  }
}

/** The script most of a locale's letters are in. The app's own name is not evidence either way. */
export function dominantScript(values) {
  const text = values.join(' ').replace(/ylih/gi, '').replace(/[^\p{L}]/gu, '')
  const counts = new Map()
  for (const ch of text) {
    for (const [name, re] of SCRIPT_TESTS) {
      if (re.test(ch)) { counts.set(name, (counts.get(name) ?? 0) + 1); break }
    }
  }
  let best = null
  for (const [name, n] of counts) if (!best || n > counts.get(best)) best = name
  return best
}

/** A locale's vocabulary as a set, for comparing two locales against each other. */
const wordsOf = (v) => (v ?? '').toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '')
  .split(/[^\p{L}\p{N}]+/u).filter((w) => w.length > 2 && w !== 'ylih')

cmds.check = (argv) => {
  const s = source()
  const want = new Set(s.translatable.keys())
  const problems = []
  const add = (tag, msg) => problems.push(`${tag}\t${msg}`)

  // The checks above this line are all things lint would also catch, only faster. The three below
  // are things lint *cannot* catch, because a wrong translation is still a well-formed one: the
  // strings are present, the placeholders line up, the plurals are complete. Only comparing a
  // locale against the source — or against the other locales — sees them.
  const longKeys = [...s.translatable]
    .filter(([, v]) => v.kind === 'string' && v.value.split(/\s+/).length >= 4)
    .map(([k]) => k)
  const bodies = new Map()
  // Latin letters someone reaches for to make English look like an African orthography. Folding
  // them is what lets the untranslated check below see through the disguise.
  const FOLD = { ɔ: 'o', ɛ: 'e', ŋ: 'n', ə: 'e', ǝ: 'e', ʉ: 'u', ɨ: 'i', ʃ: 's', ɑ: 'a' }
  const bags = new Map()
  // The lingua francas this tree has actually been mistranslated through, each with the
  // commonest grammatical words of the language itself. Thresholds are calibrated against the
  // whole tree rather than guessed: on these lists the ten locales holding Swahili score 47-80%
  // and the next locale down, Sangu, scores 27%; Chichewa separates 57% from 7%. 0.4 sits in
  // both gaps. `except` is the set that may legitimately match — the language itself, plus any
  // relative close enough to share this much grammar. Sena is the one that has come up: it
  // shares zonse, palibe, ndi, kuti and ngati with Chichewa as a Zone N neighbour and scores
  // 71%, but its own file is only 11.6% of a word-bag match with the Chichewa one that was
  // deleted, and CLDR gives Sena Lero for today, which is what values-b+seh says.
  const LINGUA_FRANCA = [
    { name: 'Swahili', except: new Set(['sw']),
      words: 'katika kwenye hii huu hili yako hakuna kitu ndani zote hizi hiyo ambayo cha wako'.split(' ') },
    { name: 'Chichewa', except: new Set(['ny', 'seh']),
      words: 'zonse palibe kuti ngati kapena komwe chilichonse pamene ndipo chomwe zake kwambiri ali izi'.split(' ') },
  ]
  const words = (v) => (v ?? '').toLowerCase().replace(/[ɔɛŋəǝʉɨʃɑ]/g, (c) => FOLD[c])
    .normalize('NFD').replace(/\p{M}+/gu, '')

  for (const l of locales()) {
    let f
    try {
      f = parse(l.path)
    } catch (e) {
      add(l.tag, `unparseable: ${e.message}`)
      continue
    }
    const got = new Set(f.keys.keys())
    const missing = [...want].filter((k) => !got.has(k))
    const extra = [...got].filter((k) => !want.has(k))
    // lint: MissingTranslation (error)
    if (missing.length) add(l.tag, `MissingTranslation: ${missing.join(',')}`)
    // an extra key is dead weight at best and a typo'd name at worst
    if (extra.length) add(l.tag, `unknown keys: ${extra.join(',')}`)

    const need = categories(l.tag)
    for (const [name, e] of f.keys) {
      const src = s.translatable.get(name)
      if (!src) continue
      if (e.kind !== src.kind) { add(l.tag, `${name}: is <${e.kind}>, source is <${src.kind}>`); continue }

      if (e.kind === 'plurals') {
        // lint: MissingQuantity (error). Categories come from ICU, so this cannot drift.
        const absent = need.filter((c) => !e.items.has(c))
        if (absent.length) add(l.tag, `MissingQuantity ${name}: ${absent.join(',')}`)
      }

      const vals = e.kind === 'string' ? [e.value] : [...e.items.values()]
      const srcPh = placeholders(src.kind === 'string' ? src.value : src.items.get('other') ?? '')
      for (const v of vals) {
        // lint: StringFormatMatches (error) — a renumbered or dropped arg is a runtime crash
        const ph = placeholders(v)
        if (ph.join() !== srcPh.join()) add(l.tag, `${name}: placeholders ${ph.join(' ') || '(none)'} != source ${srcPh.join(' ') || '(none)'}`)
        // aapt2 rejects this, and AGP reports it as an unattributed NullPointerException
        const bare = v.replace(/\\'/g, '')
        if (bare.includes("'")) add(l.tag, `${name}: unescaped apostrophe`)
        if (/&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[0-9a-fA-F]+);)/.test(v)) add(l.tag, `${name}: unescaped ampersand`)
      }
      // tools:ignore is a legitimate escape hatch in a translation (values-nb suppresses Typos on
      // "internett"), but using the prefix without declaring it is an XML parse failure.
      if (/\btools:/.test(f.lines[e.from]) && !f.lines.some((x) => x.includes('xmlns:tools')))
        add(l.tag, `${name}: uses tools: but the file has no xmlns:tools declaration`)
    }

    bodies.set(l.tag, longKeys.map((k) => f.keys.get(k)?.value ?? '').join(' '))
    bags.set(l.tag, new Set(longKeys.flatMap((k) => wordsOf(f.keys.get(k)?.value))))

    // A locale in the wrong script is invisible to every check above — the strings are all present,
    // translated and well-formed, and unreadable to the only people who asked for that language.
    // Eleven locales here held romanised text for languages nobody romanises.
    const script = expectedScript(l.tag)
    const written = dominantScript([...f.keys.values()]
      .flatMap((e) => (e.kind === 'string' ? [e.value] : [...e.items.values()])))
    if (script && written && script !== written)
      add(l.tag, `script: the file is written in ${written}, but ${l.tag} is a ${script} language`)

    // A single value left in English inside a locale that does not use the alphabet. The script
    // check above sees only the file as a whole, so a locale can be 90% translated and still hand
    // "average session" and "playing share" to a reader of Odia — which is what it did. Tech words
    // every locale keeps are stripped first, and five letters is the floor so that "max" and "app"
    // do not fill the report with things nobody would translate.
    // Only where the file is otherwise in the right script: when it is not, the line above has
    // already said so, and repeating it once per string buries every other locale in the report.
    if (script && script !== 'Latin' && written === script) {
      for (const [name, e] of f.keys) {
        for (const v of (e.kind === 'string' ? [e.value] : [...e.items.values()])) {
          const bare = v.replace(/%\d*\$?[a-zA-Z]/g, ' ')
            .replace(/\b(ylih|usb|bluetooth|ble|le|audio|json|android|connectedDevice|https?)\b/gi, ' ')
          const letters = bare.replace(/[^\p{L}]/gu, '')
          const latin = (letters.match(/\p{Script=Latin}/gu) ?? []).length
          if (latin >= 5 && latin > letters.length / 2)
            add(l.tag, `${name}: still in English ("${v.slice(0, 40)}")`)
        }
      }
    }

    // Scaffolding a generator wrote and nobody filled in. It reaches users verbatim: seven locales
    // shipped "[lv] save" on a button.
    const stubs = [...f.keys].filter(([, e]) => e.kind === 'string' && /^\[[A-Za-z+-]{2,12}\]\s/.test(e.value))
    if (stubs.length) add(l.tag, `placeholder stubs: ${stubs.length} strings still read "[tag] english"`)

    // A locale still holding the English source advertises a language through generateLocaleConfig
    // and then answers in English, which is worse than not offering it. Judged on the long strings
    // only — "bluetooth", "usb" and "%1$s · %2$s" match the source in every locale, legitimately.
    //
    // Compared on words rather than bytes, because values-b+jgo hid from a byte comparison for a
    // whole release: it is English with every o rewritten as ɔ ("everything stays ɔn this phɔne"),
    // which differs from the source in almost every character and is not a translation at all.
    const same = longKeys.filter((k) => words(f.keys.get(k)?.value) === words(s.translatable.get(k).value))
    if (same.length > longKeys.length / 2)
      add(l.tag, `untranslated: ${same.length}/${longKeys.length} long strings are still the English source`)

    // A locale holding the regional lingua franca rather than the language it claims. This is the
    // shape of a translation done through the language of the nearest city: four Chagga locales
    // here held one Swahili translation between them, and values-b+vmw held Chichewa under a
    // Makhuwa name. Nothing above sees it. The strings are present, the script is right, the text
    // is not English, and the language it actually holds is usually not in the tree to be compared
    // against — Chichewa is not, which is how vmw survived every other rule here.
    //
    // Grammatical words only. The Arabic-derived vocabulary Swahili is best known for — kila,
    // bila, wakati, baada, sababu, akaunti — is exactly what a neighbouring language borrows
    // legitimately, so a fingerprint built from it flags the whole coast.
    for (const lf of LINGUA_FRANCA) {
      if (lf.except.has(l.tag.split('-')[0])) continue
      const hit = lf.words.filter((w) => bags.get(l.tag).has(w)).length
      if (hit / lf.words.length >= 0.4)
        add(l.tag, `lingua franca: ${hit}/${lf.words.length} of ${lf.name}’s commonest grammatical words are in this file — it is probably ${lf.name}, not ${l.tag}`)
    }
  }

  // One bulk run writing its output into several folders is invisible one file at a time — each
  // locale looks translated. Only lining them up shows it: nine locales here held the same Serbian
  // text, among them Tamil, Telugu and Swahili, which do not even share a script.
  const bySig = new Map()
  for (const [tag, sig] of bodies) {
    if (!sig.replaceAll(' ', '')) continue
    if (!bySig.has(sig)) bySig.set(sig, [])
    bySig.get(sig).push(tag)
  }
  for (const group of bySig.values())
    if (group.length > 1)
      add(group[0], `duplicate locales: ${group.join(' ')} hold identical text — at most one can be right`)

  // The same bulk output copied with a word changed here and there is not byte-identical and hides
  // from the check above: values-b+sat held Samburu, sharing 99% of its words with saq. The
  // threshold is high on purpose — genuinely close languages reach 0.9 legitimately (rn and rw sit
  // there, pt and pt-BR at 0.62), and a check that cries about those is one nobody reads.
  const tags = [...bags.keys()]
  for (let i = 0; i < tags.length; i++) {
    for (let j = i + 1; j < tags.length; j++) {
      const a = bags.get(tags[i]), b = bags.get(tags[j])
      if (a.size < 20 || b.size < 20) continue
      // An exact copy is the check above's to report, not this one's.
      if (bodies.get(tags[i]) === bodies.get(tags[j])) continue
      let shared = 0
      for (const w of a) if (b.has(w)) shared++
      const overlap = shared / (a.size + b.size - shared)
      if (overlap >= 0.95)
        add(tags[i], `near-duplicate locales: ${tags[i]} and ${tags[j]} share ${Math.round(overlap * 100)}% of their words — one is a copy of the other`)
    }
  }
  // An empty values-<lang>/ folder is invisible to git status but lint reads folder names and
  // reports every string as untranslated for it. This has broken the build once already.
  for (const d of readdirSync(RES)) {
    if (!/^values-(b\+[A-Za-z+]+|[a-z]{2,3}(-r[A-Z]{2})?)$/.test(d) || NOT_LOCALES.has(d.slice(7))) continue
    if (!existsSync(join(RES, d, 'strings.xml'))) add(dirToTag(d), `folder ${d}/ has no strings.xml — lint will report every string missing`)
  }

  // lint: UnusedResources (error in this project). A new string that no Kotlin file references
  // fails the build just as hard as a missing translation, so adding copy and adding its call site
  // are one change, not two. Skipped when --no-usage or when the source tree is not where we expect.
  if (!argv.includes('--no-usage') && existsSync('app/src/main/java')) {
    const code = filesUnder('app/src/main/java').filter((p) => /\.(kt|java)$/.test(p))
      .map((p) => readFileSync(p, 'utf8')).join('\n')
    // Not every string is reached from Kotlin. The widget picker labels and descriptions are
    // named only by android:label in the manifest and android:description in
    // res/xml/widget_*_info.xml, and lint counts those as uses — so scanning code alone reports
    // them as unused forever, which is how a checker teaches people to ignore it.
    const markup = [
      ...(existsSync('app/src/main/AndroidManifest.xml') ? ['app/src/main/AndroidManifest.xml'] : []),
      ...filesUnder(RES).filter((p) => p.endsWith('.xml') && !/[/\\]values(-|[/\\])/.test(p)),
    ].map((p) => readFileSync(p, 'utf8')).join('\n')
    const unused = [...want.keys()].filter((k) =>
      !code.includes(`R.string.${k}`) && !code.includes(`R.plurals.${k}`) &&
      !markup.includes(`@string/${k}`) && !markup.includes(`@plurals/${k}`))
    if (unused.length) add('values', `UnusedResources: no R.string/R.plurals reference for ${unused.join(',')}`)
  }

  for (const p of problems) console.log(p)
  const n = locales().length
  console.error(problems.length ? `FAIL ${problems.length} problems across ${n} locales` : `OK ${n} locales, ${want.size} keys each`)
  if (problems.length && !argv.includes('--no-exit')) process.exitCode = 1
}

// ---------------------------------------------------------------------------

function filesUnder(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((d) =>
    d.isDirectory() ? filesUnder(join(dir, d.name)) : [join(dir, d.name)])
}

function flag(argv, name) {
  const i = argv.indexOf(name)
  return i < 0 ? undefined : argv[i + 1]
}
function out(argv, text) {
  const o = flag(argv, '-o')
  if (o) { writeFileSync(o, text); console.error(`wrote ${o}`) } else console.log(text)
}
function die(msg) {
  console.error(msg)
  process.exit(2)
}

// Guarded so the helpers above can be imported (by a test, or by a one-off script) without the CLI
// firing on process.argv that was never meant for it.
if (import.meta.filename === process.argv[1] || basename(process.argv[1] ?? '') === 'strings.mjs') {
  const [cmd, ...argv] = process.argv.slice(2)
  if (!cmd || !cmds[cmd]) {
    console.error('usage: strings.mjs <locales|check|get|template|apply|new-locale|fix-plurals> [args]')
    process.exit(2)
  }
  cmds[cmd](argv)
}
