# Dependabot automation, and cutting Android 6 and 7

Design for three changes that arrived together because one of them exposed the other two.
Written 2026-09-02.

## Why

PR #21 — a grouped gradle bump — has been red since 2026-08-31 and cannot go green, because
`androidx.navigation:navigation-compose 2.10.0` declares `minSdk 24` and this app's floor is 23.
Being a grouped PR it takes four healthy bumps down with it, which is the damage
`.github/dependabot.yml` already describes for the material3 pin.

Three separate gaps showed up while diagnosing it:

1. **Auto-merge was never the blocker.** `dependabot-auto-merge.yml` arms GitHub's auto-merge on
   every Dependabot PR and works correctly. What holds them is the `main protection` ruleset's
   `required_approving_review_count: 1`: Dependabot cannot approve its own pull request, so PRs
   #22 and #23 sat green for 35 hours until a human approved, and auto-merge merged them 18
   seconds later. The merge machinery is fine; the approval is the missing input.
2. **No agent watches Dependabot.** `agent-fix-ci.yml` gates on
   `startsWith(head_branch, 'agent/issue-')`, so a red Dependabot branch reaches nothing. Run
   `33365217893` skipped both jobs, by design.
3. **The floor itself is now the cost.** minSdk 23 is what makes navigation 2.10.0 unmergeable,
   and it is the second dependency this year to be held back by it.

## Scope

Three changes, independent enough to land in this order and be judged separately.

---

## 1. Approve Dependabot PRs once CI is green

New workflow `.github/workflows/dependabot-approve.yml`.

The required approval is **not** relaxed and the ruleset is not touched. `docs/agent-pipeline.md`
§6 is explicit that without it the agent review stage is decorative — auto-merge waits for
whatever the ruleset requires and nothing else, so agent PRs would land on green CI alone. What
this adds is an approval for Dependabot specifically, on the argument the auto-merge workflow's
own header already makes: for a dependency bump, CI *is* the review.

### Trigger

```yaml
on:  # zizmor: ignore[dangerous-triggers]
  workflow_run:
    workflows: ["Android CI", "Nix dev shell"]
    types: [completed]
```

Both workflows, because the ruleset requires two contexts and they come from different files:
`all-green` (the aggregation job at the bottom of `android-ci.yml`) and `devshell` (`nix.yml`).
Either can finish last.

`workflow_run` carries the same property `agent-fix-ci.yml` relies on and needs the same zizmor
exemption: the file that executes is the one on `main`, and this job never checks out the PR.

### What the job does

1. Resolve the PR from `github.event.workflow_run.head_branch` with `gh pr list --head`, rather
   than from `workflow_run.pull_requests` — that array is unpopulated in several cases and the
   branch name always works for an in-repo Dependabot branch.
2. Exit unless the PR author is `dependabot[bot]`.
3. Re-read the whole rollup — `gh pr checks "$PR" --required` — rather than trusting the run that
   woke it. **This is what makes "two workflows, either order" correct**: the first to finish
   sees the other still pending and does nothing; the last to finish sees everything green.
   The exit code carries the verdict and all three values matter: `0` every required check
   passed, `8` at least one is still pending, `1` one failed. Only `0` may approve — a bare
   `if !` would treat "still running" as "failed" and, worse, a script without `pipefail`
   discipline could read a pending rollup as success.
4. Exit if a live approval from `github-actions` already stands, so a re-run does not stack
   reviews.
5. `gh pr review --approve` with `GITHUB_TOKEN`.

Auto-merge is already armed by the existing workflow, so the approval is the last requirement and
the merge follows.

### Why this order beats approving on `opened`

The ruleset carries `dismiss_stale_reviews_on_push: true`. Every gradle PR gets a second commit —
`dependabot-verification-metadata.yml` pushes the regenerated checksums — which would dismiss an
approval posted when the PR opened. Approving from the rollup after the last push cannot hit that.

### Permissions

Top-level `permissions: {}`, granted per job as `contents: read` and `pull-requests: write`,
matching the pattern the zizmor audit established across the pipeline on `main`.

### Prerequisite already satisfied

Settings → Actions → *Allow GitHub Actions to create and approve pull requests* is on; the agent
review stage has depended on it since 2026-08-26.

### Risk to verify before calling this done

The ruleset carries `require_extra_approval_for_unattributed_changes: true`. PR #21 carries a
`github-actions[bot]` commit (`8c80f6f5`, the checksums). If that flag demands a second, human
approval for bot-authored commits, gradle PRs will still stall — and the symptom is indistinct
from success: a green, approved, unmerged PR. Verify against a real gradle PR, not by reasoning.
If it does bite, the fix is a distinct approving identity (a GitHub App), which is the same
fallback `docs/agent-pipeline.md` names for the review stage.

---

## 2. `agent-fix-dependabot.yml` — the agent fix loop for red Dependabot PRs

A **separate, self-contained workflow**, not an extension of `agent-fix.yml`. Three things in the
shared stage do not transfer, and faking them would be worse than a second file:

| `agent-fix.yml` | this workflow |
|---|---|
| checks out `agent/issue-${{ inputs.issue }}` | checks out the PR head branch |
| rounds = `git rev-list --count origin/main..HEAD` | that counts Dependabot's commit and the checksum commit; rounds count **agent-authored** commits |
| requires an `issue` for labels and the stuck comment | no issue exists |
| budget 10, with an escalation round at 9 | budget **3**, no escalation |

### Trigger and gate

`workflow_run` on `["Android CI"]`, `conclusion == 'failure'`, head branch
`startsWith('dependabot/')`. A `context` job finds the PR, skips a draft or one labelled
`agent:stop`/`agent:stuck`, and collects `gh run view --log-failed | tail -c 30000` — the same
truncation `agent-fix-ci.yml` uses and for the same reason.

### Round budget of 3

A dependency bump either compiles against the new version or it does not. Ten rounds of an agent
negotiating with a transitive constraint is how one bad bump costs a hundred CI matrices. Counted
as commits on the branch authored by `ylih agent`, read fresh each run rather than stored —
Dependabot force-pushes over its own branch on rebase and discards agent commits, which is
self-healing but must not leave a stale counter behind.

### The agent's remit, which is narrower than the issue pipeline's

**In scope** — app code that no longer builds against the new version: a renamed API, a changed
signature, a deprecation that `allWarningsAsErrors` turned into an error, a Roborazzi capture or
Compose test that needs re-recording.

**Out of scope, and this is the important half** — a version that conflicts with a *deliberate
constraint*: a minSdk floor, a pin held by hand, an ignore in `dependabot.yml`. PR #21 is exactly
this shape. The right answer there is a policy change to `libs.versions.toml` and
`.github/dependabot.yml`, which is a human decision about what the app supports. The agent writes
a comment saying which constraint it hit and changes nothing.

**Never** touch `gradle/verification-metadata.xml` — `dependabot-verification-metadata.yml` owns
that file and a second writer would race it.

**Never** weaken a gate. Same rule, same wording, as the issue pipeline.

### Giving up

A round that changes nothing, or exhausting the budget, is terminal: comment with what was tried
and the failure, label the PR `agent:stuck`, and `gh pr merge --disable-auto`.

"Changes nothing" is deliberately the same terminal path the out-of-scope case above takes. An
agent that correctly identifies a deliberate constraint and declines to patch around it leaves
the tree clean, and that is exactly the signal to stop — not to spend two more rounds discovering
it again. `agent-fix.yml` treats an empty round as a warning and continues, because there a
tenth round is still cheaper than a human; here it is not.

**This requires one edit to `dependabot-auto-merge.yml`.** That workflow re-arms every open
Dependabot PR on every push to `main`, so disabling auto-merge alone would be undone by the next
merge. Its arm step must skip a PR labelled `agent:stuck`. Without this edit the give-up path does
not hold.

### Token

`AGENT_PUSH_TOKEN` — an Actions secret with `contents: write`, and critically a PAT, so the push
triggers a new Android CI run. `GITHUB_TOKEN` starts no workflow runs, the trap `CLAUDE.md`
documents for `DEPENDABOT_PUSH_TOKEN`.

Deliberately **not** `DEPENDABOT_PUSH_TOKEN`: that lives in the Dependabot secret store, and
whether a `workflow_run` run whose triggering run was Dependabot's reads the Dependabot store or
the Actions store is the one genuinely ambiguous thing here. Using the Actions secret is correct
if it reads the Actions store; verify empirically on the first run rather than reasoning about it,
and if the token comes through empty the checkout fails loudly at step one.

Checkout needs `persist-credentials` and therefore `# zizmor: ignore[artipacked]`, with the same
one-line justification `agent-fix.yml` carries.

### Consequence, stated plainly

Together with change 1, a dependency bump can now be patched by an agent and merged to `main`
with no human reading it. This is accepted knowingly. `docs/agent-pipeline.md` states the
equivalent property for the issue pipeline in the same terms, and the mitigations are the same
ones: Android CI, the coverage floors, `r8-keep-check.py`, and the instrumented suite.

---

## 3. minSdk 23 → 26: drop Android 6 and 7

The floor moves to 26 rather than 24. 24 would clear navigation 2.10.0 and material3 alpha26 and
nothing else; 26 is the version at which **core library desugaring stops being needed at all**,
and that is the prize.

### What leaves the build

- `isCoreLibraryDesugaringEnabled` and the `coreLibraryDesugaring(libs.android.desugar.jdk.libs)`
  dependency. `java.time` is native from API 26; `java.util.stream` and `Optional` from 24.
- The `desugarJdkLibs` version and library entry in `libs.versions.toml`.
- **The entire `L8DexDesugarLibTask` block** in `app/build.gradle.kts` and its import — the
  workaround for `VerifyError: Verifier rejected class j$.util.concurrent.ThreadLocalRandom`,
  where two independently minified L8 runs defined 472 of the same `j$` names as different
  classes. That failure mode ceases to exist.
- A licensing paragraph in `docs/fdroid.md`. `com.android.tools:desugar_jdk_libs` is the one
  **GPL-2.0-with-Classpath-Exception** artifact shipping in the APK, and the doc argues at length
  why that does not reach the app's MIT licence. Dropping the dependency removes the argument.

### What collapses in app code

`Build.VERSION_CODES.O` is now always true, and `VERSION_CODES.N` with it:

- `ui/Format.kt` — `IcuUnits?` becomes non-null, the three `SDK_INT >= N` guards go, `legacy()`
  and its `@RequiresApi(N)` go. The comment *"Null on Android 6, the one release this ships to
  without `android.icu`"* stops being true.
- `tracking/TrackingController.kt` — `detailedTrackingSupported()` loses its `SDK_INT >= O &&`
  clause.
- `tracking/Notifications.kt` — `ensureChannel` becomes unconditional; `@RequiresApi(O)` goes.
- `tracking/TrackingService.kt`, `tracking/PlaybackWatcher.kt` — `@RequiresApi(O)` goes.
- `AppLocale.kt` — the `setLocale` comment ("the form that exists at the API 23 floor") is
  rewritten. The call itself stays; `setLocale` stores a one-element locale list from 24 anyway.
- `tracking/Restrictions.kt` — "on Android 6–10" becomes "on Android 8–10".

### What this removes from the product, which is more than dead code

At minSdk 26 an entire **shipping configuration disappears**: today every Android 6 and 7 install
can never run detailed tracking, and the settings screen has to say so. Three test classes exist
only to pin that behaviour and lose their subject:

- `TrackingControllerLegacyTest` — the controller refusing it at the floor
- `YlihViewModelLegacyTest` — *"a switch that springs back with no explanation reads as a bug in
  the app rather than a limit of the phone"*
- `SettingsScreenLegacyTest` — the screen saying so, and hiding the playback choice with it

`WidgetsLegacyTest` survives: it is about the API < 31 dynamic-colour fallback, which minSdk 26
does not reach. It retargets `M` → `O`.

### The coverage risk this creates, and the plan for it

`CLAUDE.md` states the rule: *"Where a path can be reached without the flavor split — the API 23
floor, say, where no build can run the foreground service — prefer that, and the `*LegacyTest`
classes are where it lives."*

The `detailedSupported == false` branch has two routes today. After this change only the
**Play-only** route survives — API 34+ with Bluetooth denied — so on the **classic** report that
branch stops being reachable at all, and classic is the flavor the 95 / 99 / 70 floors are quoted
against (currently 96.3 / 99.1 / 77.1).

**Plan:** retarget the three classes to the Play flavor's Bluetooth-denied route rather than
deleting them, keeping the branch covered on at least one flavor. Then measure classic before
touching anything else.

**If classic drops below a floor, that is a finding to bring back, not a floor to lower.** The
agent prompts in this repository already forbid exactly that move, and this spec is held to the
same rule.

Good news on the neighbouring gate: `settings_detailed_unavailable` is already worded only about
Bluetooth ("*unavailable until bluetooth access is granted…android 14+ ties to a bluetooth
permission*"), so no string goes unused and `UnusedResources` — a hard lint gate — stays quiet.

### Other test edits

- `FormatTest` — delete *"below android 7 the units fall back to the ones every language used to
  get"*, whose subject is `legacy()`.
- `PermissionRationaleTest:54` — `@Config(sdk = [M, S_V2])` → `[O, S_V2]`.
- `app/lint.xml:42` — the comment explaining why `NewApi`/`InlinedApi` are off for `src/test`
  names the "minSdk 23 floor". The exemption stays valid; the number changes.

### CI

`android-ci.yml`'s instrumented matrix: the floor leg moves `apiLevel: 23` → `26`. Its comment
must be rewritten — the stated reason for that leg is running desugared `java.time` on a runtime
that desugars it, which after this change is not a thing. The new reason is the minSdk floor
itself.

The check name is `instrumented (minified, minSdk)` and is built from `matrix.label`, not the API
level, **so the required-status-check contexts in the ruleset do not change.** No ruleset edit.

Verify an API 26 system image is actually available to `reactivecircus/android-emulator-runner`
before relying on that leg.

### Dependency pins this unblocks

- `navigationCompose` → 2.10.0 (PR #21's bump).
- `material3` → off the hand-held 1.5.0-alpha25 pin, and **remove the
  `androidx.compose.material3:material3` entry from `.github/dependabot.yml`'s ignore list** plus
  the comment explaining it. Both exist only because of the minSdk 23 floor.
- `gradle/verification-metadata.xml` regenerated over the full CI task set, with `GRADLE_OPTS=`
  cleared, per the command in `CLAUDE.md` — and then proved with a second
  `--refresh-dependencies` run, since generation and verification are different code paths.

### Docs

`CLAUDE.md` (the minSdk 23 references at lines ~60 and ~118, and the desugaring/L8 paragraphs in
Testing), `docs/fdroid.md` (the licence paragraph), `.github/dependabot.yml`,
`gradle/libs.versions.toml`. `README.md` and the fastlane metadata make no Android-version claim —
verified, nothing to change there.

---

## Verification

Nothing here is unit-testable; workflows are proved by running them and the floor change by the
existing gates.

- `nix shell nixpkgs#actionlint -c actionlint` and `zizmor --offline .github/workflows/` — both
  run in CI's `listing` job, which feeds `all-green`.
- The full local CI set per flavor, per `CLAUDE.md`.
- Classic coverage compared against 96.3 / 99.1 / 77.1 before and after.
- `GRADLE_OPTS= ./gradlew --refresh-dependencies assembleClassicRelease` to prove the regenerated
  metadata.
- Change 1 against a real gradle PR, watching specifically for the
  `require_extra_approval_for_unattributed_changes` risk above.
- Change 2's token store, on its first real run.

## Out of scope

- Relaxing `required_approving_review_count` or any other ruleset rule.
- Auto-approving anything that is not a Dependabot PR.
- Changing how `agent-fix.yml` serves the issue pipeline.
- Raising `targetSdk` or `compileSdk`.
