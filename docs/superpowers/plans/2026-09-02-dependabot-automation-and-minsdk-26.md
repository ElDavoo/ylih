# Dependabot automation and minSdk 26 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let Dependabot PRs approve, self-repair and merge without a human, and raise the app's floor to Android 8 (API 26) so the two dependencies currently held back by minSdk 23 can land.

**Architecture:** Two new `workflow_run` workflows sit beside the existing Dependabot machinery rather than inside it — one approves a PR once every *required* check is green, one hands a red Dependabot branch to the agent with a 3-round budget. The floor change is a separate, larger piece of work: minSdk 26 makes core library desugaring unnecessary, which removes the L8 workaround, the `java.time` backport and one GPL-licensed artifact from the APK, and collapses every `SDK_INT >= N`/`>= O` guard in the app.

**Tech Stack:** GitHub Actions (`workflow_run`, `gh` CLI, actionlint, zizmor), Kotlin/Compose/Room on AGP 9, Robolectric, JaCoCo, R8/L8.

**Spec:** `docs/superpowers/specs/2026-09-02-dependabot-automation-and-minsdk-26-design.md`

## Global Constraints

- **Land as three pull requests**, in task order: Task 1 → PR A, Tasks 2–3 → PR B, Tasks 4–9 → PR C. Task 3's give-up path does not hold without Task 2, so they ship together.
- **Lint is a hard gate.** `checkAllWarnings`, `warningsAsErrors`, `abortOnError` are all on, and there is no baseline file and must not be one. A warning fails the build exactly like an error.
- **The Kotlin compiler has `allWarningsAsErrors`.** A deprecation fails the build.
- **Never weaken a gate to pass it.** Lowering a coverage floor, adding a lint baseline, disabling a check in `app/lint.xml`, or deleting a failing assertion is not a fix. If a floor is genuinely breached, stop and report it.
- **Coverage floors, checked by CI per flavor:** `--min-instruction=95 --min-line=99 --min-branch=70`. Classic currently measures 96.3 / 99.1 / 77.1.
- **Every user-visible string is a resource**, and `MissingTranslation` is an error across 77 locales. No new English string may be added without all 77 translations. This plan adds none.
- **`UnusedResources` is a hard gate.** A string left with no reader fails the build.
- **All workflow files must pass `actionlint` and `zizmor --offline`**, both run in `android-ci.yml`'s `listing` job, which feeds the required `all-green` context.
- **Every action is pinned by commit SHA.** Copy the exact SHAs used elsewhere in the repo; do not resolve new ones.
- **Workflow permissions are granted per job**, with `permissions: {}` at the top level. This is the pattern the zizmor audit established across the pipeline.
- **Room's schema is exported and there is no fallback migration.** No task here touches an entity; if one appears to, stop.
- **Commit messages:** plain-language subject, body explaining reasoning and evidence. Match the repository's habit of explaining *why*.

---

## File Structure

**PR A — approval**
- Create `.github/workflows/dependabot-approve.yml` — resolves the PR behind a finished CI run, and approves it when every required check has passed.

**PR B — agent fix for Dependabot**
- Modify `.github/workflows/dependabot-auto-merge.yml` — skip PRs labelled `agent:stuck`.
- Create `.github/workflows/agent-fix-dependabot.yml` — self-contained fix stage: context job (find PR, collect logs, count rounds) plus fix job (checkout, run the agent, push, or give up).

**PR C — minSdk 26**
- Modify `app/build.gradle.kts` — floor, desugaring, L8 block.
- Modify `gradle/libs.versions.toml` — drop `desugarJdkLibs`, unpin `material3`, bump `navigationCompose`.
- Modify `.github/dependabot.yml` — drop the material3 ignore.
- Modify `app/src/main/java/it/eldavo/ylih/ui/Format.kt` — collapse the ICU version split.
- Modify four files under `app/src/main/java/it/eldavo/ylih/tracking/` and `AppLocale.kt` — drop `@RequiresApi(O)` and the `SDK_INT` guards.
- Modify three `*LegacyTest` classes plus `FormatTest` and `PermissionRationaleTest`.
- Modify `.github/workflows/android-ci.yml` — the minSdk emulator leg.
- Modify `CLAUDE.md`, `docs/fdroid.md`, `app/lint.xml` comments.
- Regenerate `gradle/verification-metadata.xml`.

---

## Task 1: Approve Dependabot PRs once every required check is green

**Files:**
- Create: `.github/workflows/dependabot-approve.yml`

**Interfaces:**
- Consumes: the `all-green` job in `.github/workflows/android-ci.yml` and the `devshell` job in `.github/workflows/nix.yml` — the two contexts the `main protection` ruleset requires.
- Produces: an approving review from `github-actions[bot]` on Dependabot PRs. Nothing else reads this; the existing `dependabot-auto-merge.yml` does the merge.

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/dependabot-approve.yml`:

```yaml
name: Dependabot approve

# Dependabot cannot approve its own pull request, and the `main protection` ruleset requires one
# approving review. So every Dependabot PR went green and then sat there: #22 and #23 waited 35
# hours for a human, and auto-merge merged them 18 seconds after the approval arrived. The merge
# machinery was never the problem; the approval was the missing input.
#
# The ruleset is deliberately not relaxed. docs/agent-pipeline.md is explicit that without the
# approval rule the agent review stage is decorative -- auto-merge waits for whatever the ruleset
# requires and nothing else, so an agent PR would land on green CI alone. What this file adds is
# an approval for Dependabot specifically, on the argument dependabot-auto-merge.yml already
# makes: for a dependency bump, CI is the review.

# workflow_run, and both producers of a required check. The ruleset requires `all-green`
# (android-ci.yml) and `devshell` (nix.yml); they are different workflows and either can finish
# last. Like agent-fix-ci.yml this runs the file from main rather than from the branch, and it
# never checks the pull request out at all.
on:  # zizmor: ignore[dangerous-triggers]
  workflow_run:
    workflows: ["Android CI", "Nix dev shell"]
    types: [completed]

permissions: {}

# Both required checks finishing at once would otherwise have two runs approving the same PR.
concurrency:
  group: dependabot-approve-${{ github.event.workflow_run.head_branch }}
  cancel-in-progress: false

jobs:
  approve:
    if: github.event.workflow_run.event == 'pull_request'
    permissions:
      contents: read
      pull-requests: write
    runs-on: ubuntu-latest

    steps:
      - name: Approve if every required check has passed
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          GH_REPO: ${{ github.repository }}
          BRANCH: ${{ github.event.workflow_run.head_branch }}
        run: |
          set -euo pipefail

          # The branch, not github.event.workflow_run.pull_requests: that array is empty in
          # several documented cases, and a Dependabot branch is always in this repository.
          pr=$(gh pr list --head "$BRANCH" --state open \
                 --json number,author --jq '.[0] | select(.author.login == "app/dependabot") | .number')
          if [ -z "$pr" ]; then
            echo "::notice::No open Dependabot pull request for $BRANCH."
            exit 0
          fi

          # Read the whole rollup rather than trusting the run that woke us. This is what makes
          # "two workflows, either order" correct: the first to finish sees the other still
          # pending and does nothing; the last sees everything green.
          #
          # All three exit codes matter and none may be collapsed. 0 is every required check
          # passed, 8 is at least one still pending, 1 is one failed -- so a bare `if !` would
          # read "still running" as "failed", and anything that treats a non-zero as "approve"
          # would approve a pull request whose checks nobody waited for.
          status=0
          gh pr checks "$pr" --required > /tmp/checks.txt 2>&1 || status=$?
          cat /tmp/checks.txt
          if [ "$status" -ne 0 ]; then
            echo "::notice::PR #$pr is not green yet (gh pr checks exited $status)."
            exit 0
          fi

          # A re-run must not stack reviews. Only a review that still stands counts: the ruleset
          # has dismiss_stale_reviews_on_push, so an approval dismissed by a later push reads as
          # DISMISSED here and this correctly approves again.
          approved=$(gh pr view "$pr" --json reviews \
                       --jq '[.reviews[] | select(.author.login == "github-actions" and .state == "APPROVED")] | length')
          if [ "$approved" -gt 0 ]; then
            echo "::notice::PR #$pr already carries a live approval."
            exit 0
          fi

          gh pr review "$pr" --approve \
            --body 'Every required check is green. Approved automatically: for a dependency bump, CI is the review.'
```

- [ ] **Step 2: Lint it exactly as CI does**

Run:

```bash
nix shell nixpkgs#actionlint -c actionlint .github/workflows/dependabot-approve.yml
```

Expected: no output, exit 0.

Then zizmor, at the version CI pins:

```bash
nix shell nixpkgs#zizmor -c zizmor --offline .github/workflows/dependabot-approve.yml
```

Expected: no findings. If it reports `dangerous-triggers`, confirm the `# zizmor: ignore[dangerous-triggers]` comment is on the `on:` line itself — it must be on that line, not the line above.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/dependabot-approve.yml
git commit -m "Approve Dependabot pull requests once CI is green

Dependabot cannot approve its own pull request and the ruleset requires
one approving review, so every Dependabot PR went green and stopped.
PRs #22 and #23 sat for 35 hours and merged 18 seconds after a human
approved them; #16 merged unattended on 26 August only because it
predates the approval rule, which was added on the 28th to make the
agent review stage mean something.

The ruleset is untouched. This approves Dependabot specifically, and
reads the whole required-check rollup rather than the run that woke it,
so it is correct whichever of the two required workflows finishes last."
```

- [ ] **Step 4: Verify on a real pull request, and watch for the one known risk**

There is no way to test a `workflow_run` workflow except by running it. After PR A merges, the next Dependabot PR is the test.

Expected: the PR goes green, an approval from `github-actions` appears, auto-merge merges it.

**If it goes green, gets the approval, and still does not merge**, that is the `require_extra_approval_for_unattributed_changes: true` risk from the spec: the ruleset may demand a second approval because gradle PRs carry a `github-actions[bot]` commit from `dependabot-verification-metadata.yml`. Confirm with:

```bash
gh pr view <N> --json reviewDecision,mergeStateStatus
```

`reviewDecision: "REVIEW_REQUIRED"` on a PR that already has one approval is the signature. The fix is to give the approval its own GitHub App identity — the same fallback `docs/agent-pipeline.md` names for the review stage — not to remove the ruleset flag.

---

## Task 2: Let a label stop auto-merge re-arming

**Files:**
- Modify: `.github/workflows/dependabot-auto-merge.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: the guarantee Task 3's give-up path depends on — a PR labelled `agent:stuck` is never re-armed.

**Why this is its own task:** `dependabot-auto-merge.yml` re-arms every open Dependabot PR on every push to `main`. Without this change, Task 3 calling `gh pr merge --disable-auto` is undone by the next merge to `main`, and a PR the agent gave up on silently rejoins the queue.

- [ ] **Step 1: Add the label check to the arm loop**

In `.github/workflows/dependabot-auto-merge.yml`, inside the `while read -r pr` loop, immediately after `echo "::group::$pr"`, insert:

```bash
            # agent-fix-dependabot.yml labels a pull request it has given up on and disarms
            # auto-merge. This loop re-arms every open Dependabot PR on every push to main, so
            # without this check the give-up would be undone by the next merge.
            if gh pr view "$pr" --json labels --jq '[.labels[].name] | join(",")' \
                 | grep -q 'agent:stuck'; then
              echo "skipping: labelled agent:stuck"
              echo "::endgroup::"
              continue
            fi
```

- [ ] **Step 2: Lint**

```bash
nix shell nixpkgs#actionlint -c actionlint .github/workflows/dependabot-auto-merge.yml
```

Expected: no output. actionlint runs shellcheck over the `run:` block; a `continue` inside the `while` loop is correct and should not warn.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/dependabot-auto-merge.yml
git commit -m "Do not re-arm auto-merge on a pull request the agent gave up on

This loop re-arms every open Dependabot PR on every push to main, which
is what closes the window where GitHub disarms auto-merge by itself when
the base branch moves. The next commit adds a fix stage that disarms
auto-merge deliberately when it cannot repair a bump, and without this
check the very next merge to main would undo that."
```

---

## Task 3: `agent-fix-dependabot.yml`

**Files:**
- Create: `.github/workflows/agent-fix-dependabot.yml`

**Interfaces:**
- Consumes: `.github/actions/android-setup` (composite, no inputs); secrets `AGENT_PUSH_TOKEN` and `CLAUDE_CODE_OAUTH_TOKEN`; the `agent:stuck` label from Task 2.
- Produces: commits authored `ylih agent <dpfuturehacker@gmail.com>` on Dependabot branches. Task 2's label check reads the label this writes.

**Why a separate file rather than reusing `agent-fix.yml`:** that workflow checks out `agent/issue-${{ inputs.issue }}`, counts rounds as `git rev-list --count origin/main..HEAD`, requires an `issue` number for its labels and stuck comment, and budgets 10 rounds with an escalation at 9. All four are wrong here.

- [ ] **Step 1: Write the workflow**

Create `.github/workflows/agent-fix-dependabot.yml`:

```yaml
name: Agent · fix Dependabot

# Android CI finished failing on a Dependabot branch. Gather what failed and let the agent try to
# repair the bump. See docs/agent-pipeline.md for the issue-driven sibling of this file.
#
# Deliberately not a caller of agent-fix.yml. That workflow is built around an issue: it checks
# out agent/issue-N, counts rounds as commits since main, and labels the issue when it gives up.
# A Dependabot branch has no issue, and its commits since main are Dependabot's own plus the
# checksum commit -- so every one of those would have to be faked.

# workflow_run is the trigger this needs, not a convenience: the job reads the failed run's logs,
# which a check on the pull request cannot reach. It runs the workflow file from main rather than
# the branch under test, so the branch cannot rewrite what executes here.
on:  # zizmor: ignore[dangerous-triggers]
  workflow_run:
    workflows: ["Android CI"]
    types: [completed]

# Granted per job: the context job needs only to read, and the job that runs the agent is the one
# holding a push token.
permissions: {}

jobs:
  context:
    permissions:
      contents: read
      actions: read
    if: >-
      github.event.workflow_run.conclusion == 'failure'
      && startsWith(github.event.workflow_run.head_branch, 'dependabot/')
    runs-on: ubuntu-latest
    outputs:
      pr: ${{ steps.find.outputs.pr }}
      logs: ${{ steps.logs.outputs.text }}
      go: ${{ steps.find.outputs.go }}

    steps:
      - name: Find the pull request
        id: find
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          GH_REPO: ${{ github.repository }}
          BRANCH: ${{ github.event.workflow_run.head_branch }}
        run: |
          set -euo pipefail
          pr=$(gh pr list --head "$BRANCH" --state open --json number --jq '.[0].number // empty')
          if [ -z "$pr" ]; then
            echo "::notice::No open pull request for $BRANCH — nothing to fix."
            echo "go=false" >> "$GITHUB_OUTPUT"
            exit 0
          fi
          # A stopped or given-up branch is left alone, and so is a draft.
          state=$(gh pr view "$pr" --json isDraft,labels --jq '[(.isDraft|tostring)] + [.labels[].name] | join(",")')
          case ",$state," in
            *,true,*|*,agent:stop,*|*,agent:stuck,*)
              echo "::notice::PR #$pr is draft or stopped — leaving it alone."
              echo "go=false" >> "$GITHUB_OUTPUT"
              exit 0 ;;
          esac
          {
            echo "pr=$pr"
            echo "go=true"
          } >> "$GITHUB_OUTPUT"

      - name: Collect the failed job logs
        id: logs
        if: steps.find.outputs.go == 'true'
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          GH_REPO: ${{ github.repository }}
          RUN_ID: ${{ github.event.workflow_run.id }}
          RUN_URL: ${{ github.event.workflow_run.html_url }}
        run: |
          set -euo pipefail
          # Truncated to the tail, hard: the whole thing has to fit in an environment variable,
          # and the tail is where the failure is. The head is SDK downloads.
          gh run view "$RUN_ID" --log-failed 2>/dev/null | tail -c 30000 > /tmp/failed.log || true
          if [ ! -s /tmp/failed.log ]; then
            printf 'Could not download the failed job logs. Run: %s\n' "$RUN_URL" > /tmp/failed.log
          fi
          {
            printf 'text<<CI_LOG_EOF\n'
            cat /tmp/failed.log
            printf '\nCI_LOG_EOF\n'
          } >> "$GITHUB_OUTPUT"

  fix:
    needs: [context]
    if: needs.context.outputs.go == 'true'
    permissions:
      contents: read
      # The action fetches a GitHub OIDC token on every run, including the OAuth path, and fails
      # after three retries without this.
      id-token: write
      pull-requests: write
    runs-on: ubuntu-latest
    timeout-minutes: 60
    concurrency:
      group: agent-fix-dependabot-${{ needs.context.outputs.pr }}
      cancel-in-progress: false

    steps:
      - name: Checkout the Dependabot branch
        # the PAT below is what pushes this branch, so it has to persist.
        uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7  # zizmor: ignore[artipacked]
        with:
          token: ${{ secrets.AGENT_PUSH_TOKEN }}
          ref: ${{ github.event.workflow_run.head_branch }}
          fetch-depth: 0

      # Rounds are counted in commits this agent has already written on the branch, read fresh
      # every run rather than stored. Dependabot force-pushes over its own branch when it rebases
      # and discards those commits; that is self-healing, but a stored counter would outlive the
      # work it was counting.
      #
      # Three, not ten. A bump either compiles against the new version or it does not, and the
      # failures that need ten rounds are the ones this stage is told not to attempt at all.
      - name: Count the round
        id: round
        run: |
          set -euo pipefail
          done_rounds=$(git log --author='ylih agent' --oneline origin/main..HEAD | wc -l)
          round=$(( done_rounds + 1 ))
          echo "round=$round" >> "$GITHUB_OUTPUT"
          if [ "$round" -gt 3 ]; then echo "stuck=true"  >> "$GITHUB_OUTPUT"
          else                        echo "stuck=false" >> "$GITHUB_OUTPUT"; fi
          echo "fix round $round of 3"

      - name: Set up the Android toolchain
        if: steps.round.outputs.stuck != 'true'
        uses: ./.github/actions/android-setup

      - name: Write the context out
        if: steps.round.outputs.stuck != 'true'
        env:
          PAYLOAD: ${{ needs.context.outputs.logs }}
        run: printf '%s\n' "$PAYLOAD" > /tmp/failure.txt

      - name: Fix
        id: fix
        if: steps.round.outputs.stuck != 'true'
        continue-on-error: true
        uses: anthropics/claude-code-action@a874e9ecd7bb36efdad65429c6b35815f5a08f10 # v1
        with:
          claude_code_oauth_token: ${{ secrets.CLAUDE_CODE_OAUTH_TOKEN }}
          prompt: |
            This is fix round ${{ steps.round.outputs.round }} of 3 on pull request
            #${{ needs.context.outputs.pr }}, a Dependabot dependency bump. The branch is
            checked out. Android CI failed; the failed jobs' logs are in /tmp/failure.txt.

            Read CLAUDE.md first. Most CI failures in this repository are a documented gate
            rather than a bug, and the file says what each one wants: a lint warning is a build
            failure, a Kotlin deprecation is a build failure, a missing translation is a build
            failure, the coverage gate has floors on instruction, line and branch, and
            r8-keep-check.py guards what R8 may rename.

            Your remit here is narrower than it would be on a feature branch.

            IN SCOPE — the app no longer builds against the new version of a dependency:
            a renamed or moved API, a changed signature, a deprecation that allWarningsAsErrors
            turned into an error, a Roborazzi capture or a Compose test that needs re-recording
            because a library changed what it draws. Fix the app code.

            OUT OF SCOPE — the new version conflicts with a constraint this repository holds on
            purpose. A minSdk floor, a version pinned by hand with a comment saying why, an
            entry in .github/dependabot.yml's ignore list. The manifest-merger error
            "uses-sdk:minSdkVersion N cannot be smaller than version M declared in library" is
            the clearest example. Those are decisions about what the app supports, and they
            belong to a human. Do not pin, unpin, or edit an ignore list to get past one.
            Write a short comment saying exactly which constraint was hit and why the bump
            cannot land without changing it, change nothing else, and stop.

            NEVER edit gradle/verification-metadata.xml. dependabot-verification-metadata.yml
            owns that file and a second writer would race it.

            NEVER weaken a gate to pass it. Lowering a coverage floor, adding a lint baseline,
            disabling a check in app/lint.xml, or deleting a failing assertion is not a fix.

            Diagnose before you change anything, then make the smallest change that fixes the
            cause and re-run the cheap gates:

                node .claude/skills/translations/strings.mjs check
                ./gradlew --no-daemon lintClassicReleaseTest
                ./gradlew --no-daemon testClassicReleaseTestUnitTest

            Do not commit or push. Leave your work in the working tree.
          claude_args: >-
            --max-turns 120
            --allowedTools "Read,Write,Edit,Glob,Grep,Bash"
            --disallowedTools "Bash(gh:*),Bash(curl:*),Bash(wget:*),Bash(nc:*),Bash(ssh:*),Bash(scp:*),Bash(git push:*),Bash(git remote:*),Bash(git config:*),WebFetch,WebSearch"

      # Deliberately not .github/actions/agent-stall, and not agent-retry.yml. That pair
      # recovers a usage limit by re-running the stalled run five hours later, and it cannot
      # reach a pull request: agent-retry.yml sweeps with `gh issue list`, which excludes pull
      # requests entirely, finds the branch with a hardcoded `--head agent/issue-N`, and only
      # re-runs workflows on a five-name allowlist. Three edits to a security-sensitive sweep,
      # for a case whose recovery is a human taking a label off. So a stalled run here takes the
      # same visible exit as a failed one: commented, labelled, disarmed. Silence is the one
      # outcome worth engineering against, and this is not silent.
      - name: Commit and push
        id: push
        if: steps.round.outputs.stuck != 'true' && steps.fix.outcome == 'success'
        env:
          ROUND: ${{ steps.round.outputs.round }}
          BRANCH: ${{ github.event.workflow_run.head_branch }}
        run: |
          set -euo pipefail
          git config user.name  'ylih agent'
          git config user.email 'dpfuturehacker@gmail.com'
          git add -A
          if git diff --cached --quiet; then
            # Terminal, unlike the issue pipeline's equivalent. An empty round here is the
            # expected shape of the out-of-scope case: an agent that correctly identifies a
            # deliberate constraint and declines to patch around it leaves the tree clean, and
            # that is the signal to stop rather than to spend two more rounds rediscovering it.
            echo "empty=true" >> "$GITHUB_OUTPUT"
            exit 0
          fi
          echo "empty=false" >> "$GITHUB_OUTPUT"
          git commit -m "fix round ${ROUND}: repair the bump against the app"
          # force-with-lease because Dependabot may have rebased under us.
          git push --force-with-lease origin "HEAD:${BRANCH}"

      - name: Give up
        if: >-
          always() && needs.context.outputs.go == 'true'
          && (steps.round.outputs.stuck == 'true'
              || steps.fix.outcome == 'failure'
              || steps.push.outputs.empty == 'true')
        env:
          GH_TOKEN: ${{ secrets.AGENT_PUSH_TOKEN }}
          PR: ${{ needs.context.outputs.pr }}
          ROUND: ${{ steps.round.outputs.round }}
          STALLED: ${{ steps.fix.outcome == 'failure' }}
          PAYLOAD: ${{ needs.context.outputs.logs }}
        run: |
          set -euo pipefail
          tick='`'
          fence='~~~'
          {
            if [ "$STALLED" = true ]; then
              printf '## the fix stage stopped before it finished\n\n'
              printf 'Claude did not complete round %s — a usage limit is the usual cause. '  "$ROUND"
              printf 'Nothing was pushed, so this branch is exactly as it was.\n\n'
              printf 'Unlike the issue pipeline there is no five-hourly retry here: take the '
              printf '%sagent:stuck%s label off to let the next CI failure pick it up again, '  "$tick" "$tick"
              printf 'or just close this pull request and let Dependabot raise it next Monday.\n\n'
            elif [ "$ROUND" -gt 3 ]; then
              printf '## stuck after 3 fix rounds\n\n'
            else
              printf '## stopping: this needs a decision, not a patch\n\n'
              printf 'Round %s changed nothing, which is how this stage reports a bump blocked '  "$ROUND"
              printf 'by a constraint held on purpose — a minSdk floor, a hand-held pin, an '
              printf 'ignore list entry. See the comment above this one.\n\n'
            fi
            printf 'Auto-merge is disarmed. Nothing automated will touch this pull request '
            printf 'again until the %sagent:stuck%s label comes off.\n\n' "$tick" "$tick"
            printf '### the failure it could not get past\n\n'
            printf '%s\n' "$fence"
            printf '%s\n' "$PAYLOAD" | tail -n 80
            printf '%s\n' "$fence"
          } > /tmp/stuck.md
          gh pr comment "$PR" --body-file /tmp/stuck.md
          gh pr edit "$PR" --add-label 'agent:stuck'
          gh pr merge --disable-auto "$PR"
```

- [ ] **Step 2: Lint both files exactly as CI does**

```bash
nix shell nixpkgs#actionlint -c actionlint
nix shell nixpkgs#zizmor -c zizmor --offline .github/workflows/
```

nixpkgs currently carries actionlint 1.7.12 and zizmor 1.29.0, which are the exact versions `android-ci.yml` downloads and checksums — verified, so these two commands reproduce CI rather than approximating it.

Expected: no output from either. Run over the whole directory, not just the new file — actionlint type-checks `${{ }}` against each event's payload, and this is the check that catches a `needs.context.outputs.*` reference that does not exist.

- [ ] **Step 3: Confirm the give-up path is reachable in all three of its shapes**

There are three ways this workflow stops: the round budget ran out, the agent ran and changed nothing, and the agent did not finish. All three land in `Give up`, and the `if:` uses `always()` because a `continue-on-error` step that failed leaves the job in a state where a plain `if:` would skip everything after it.

Confirm by reading, not by running:

- `steps.fix.outcome` is `success`, `failure` or `skipped`. `continue-on-error` rewrites `conclusion` to `success` but leaves `outcome` truthful — testing `conclusion` here is the classic mistake and would make the stall branch dead code.
- `steps.push.outputs.empty` is unset when the push step was skipped, so the `== 'true'` comparison is false rather than an error.
- `needs.context.outputs.go == 'true'` is in the condition so `always()` cannot resurrect the job on a PR that was deliberately left alone.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/agent-fix-dependabot.yml
git commit -m "Hand a red Dependabot pull request to the agent

agent-fix-ci.yml gates on the agent/issue- branch prefix, so a red
Dependabot branch reached nothing at all — run 33365217893 skipped both
its jobs, by design. PR #21 has been red since 31 August with no one
looking at it.

A separate file rather than a caller of agent-fix.yml: that workflow is
built around an issue it checks out by number, counts rounds as commits
since main, and labels the issue when it gives up. All three are wrong
for a Dependabot branch, and faking them would be worse than a second
file.

Three rounds rather than ten, because a bump either compiles against the
new version or it does not. The failures that would need ten rounds are
the ones the prompt refuses to attempt: a bump blocked by a minSdk floor
or a hand-held pin is a decision about what the app supports, and an
empty round is how that gets reported."
```

- [ ] **Step 5: Verify on the first red Dependabot PR**

Expected on an in-scope failure: a `fix round 1` commit appears on the branch, CI re-runs, and (with Task 1 in place) the PR approves and merges.

Expected on an out-of-scope failure — which is what PR #21 would produce if it were still open: no commit, a comment naming the constraint, the `agent:stuck` label, auto-merge disarmed.

**Watch the very first run for the token.** If `actions/checkout` fails at step one with an empty token, `workflow_run` runs triggered by a Dependabot run read the *Dependabot* secret store rather than the Actions store. The fix is to add `AGENT_PUSH_TOKEN` to the Dependabot secret store as well; do not switch to `DEPENDABOT_PUSH_TOKEN`, which would then break if the store is the Actions one.

---

## Task 4: Move the floor to API 26 and remove core library desugaring

**Files:**
- Modify: `app/build.gradle.kts` (`minSdk`, `compileOptions`, the `L8DexDesugarLibTask` block and its import, the `coreLibraryDesugaring` dependency)
- Modify: `gradle/libs.versions.toml` (`desugarJdkLibs`)

**Interfaces:**
- Consumes: nothing.
- Produces: `minSdk = 26`. Every later task in PR C depends on it.

- [ ] **Step 1: Raise the floor**

In `app/build.gradle.kts`, line 23:

```kotlin
        minSdk = 26
```

- [ ] **Step 2: Turn desugaring off**

Replace the `compileOptions` block (lines 172–178):

```kotlin
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
```

The comment that was there explained why desugaring existed — `java.time` is native from API 26, which is now the floor — so it goes with the setting rather than being reworded.

- [ ] **Step 3: Delete the L8 workaround and its import**

Delete the whole `tasks.withType<L8DexDesugarLibTask>().configureEach { ... }` block together with the ~20 lines of comment above it that begin `// The desugared library (`j$.**` ...`, and delete line 3:

```kotlin
import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
```

This is the workaround for `VerifyError: Verifier rejected class j$.util.concurrent.ThreadLocalRandom`, where two independently minified L8 runs defined 472 of the same `j$` names as different classes. With no desugared library there are no L8 runs and the failure mode ceases to exist.

- [ ] **Step 4: Drop the dependency and the version**

In `app/build.gradle.kts`, delete:

```kotlin
    coreLibraryDesugaring(libs.android.desugar.jdk.libs)
```

In `gradle/libs.versions.toml`, delete line 46 and line 93:

```toml
desugarJdkLibs = "2.1.5"
android-desugar-jdk-libs = { module = "com.android.tools:desugar_jdk_libs", version.ref = "desugarJdkLibs" }
```

- [ ] **Step 5: Build both flavors**

```bash
./gradlew assembleClassicDebug assemblePlayDebug
```

Expected: PASS. A failure naming `java.time`, `java.util.stream` or `Optional` would mean something still needs the backport — that would be a genuine finding; stop and report it rather than restoring the dependency silently.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts gradle/libs.versions.toml
git commit -m "Raise the floor to Android 8 and drop core library desugaring

minSdk 23 has now held back two dependencies: material3 past
1.5.0-alpha25, and navigation past 2.9.8, whose 2.10.0 declares minSdk
24 and has kept PR #21 unmergeable since 31 August.

26 rather than 24 because 26 is where the desugared library stops being
needed at all — java.time is native from there — which takes with it the
L8 workaround for the VerifyError two independently minified L8 runs
caused by defining 472 of the same j\$ names as different classes."
```

---

## Task 5: Collapse the API guards the new floor makes constant

**Files:**
- Modify: `app/src/main/java/it/eldavo/ylih/ui/Format.kt`
- Modify: `app/src/main/java/it/eldavo/ylih/tracking/TrackingController.kt:71-77`
- Modify: `app/src/main/java/it/eldavo/ylih/tracking/Notifications.kt:36,39`
- Modify: `app/src/main/java/it/eldavo/ylih/tracking/TrackingService.kt:48`
- Modify: `app/src/main/java/it/eldavo/ylih/tracking/PlaybackWatcher.kt:22`
- Modify: `app/src/main/java/it/eldavo/ylih/AppLocale.kt:109-111`
- Modify: `app/src/main/java/it/eldavo/ylih/tracking/Restrictions.kt:34`

**Interfaces:**
- Consumes: `minSdk = 26` from Task 4.
- Produces: `Formatters.duration(hours: Long, minutes: Long, seconds: Long): String` and `Formatters.hours(value: Double): String` keep their signatures. `TrackingController.detailedTrackingSupported(): Boolean` keeps its signature. Task 6 asserts against both.

**Why this is not optional tidying:** `@RequiresApi` and an `SDK_INT` comparison that can never be false are exactly what lint's `ObsoleteSdkInt` reports, and lint is a hard gate with `warningsAsErrors`. Leaving them in fails the build.

- [ ] **Step 1: Collapse `Format.kt`**

Replace the `icu` property and the two guarded methods (lines 63–91) with:

```kotlin
    private val icu: IcuUnits = IcuUnits(locale)

    fun duration(hours: Long, minutes: Long, seconds: Long): String =
        icu.duration(hours, minutes, seconds)

    fun hours(value: Double): String = icu.hours(value)
```

Delete the `private fun legacy(...)` function entirely, and delete `@RequiresApi(Build.VERSION_CODES.N)` from `private class IcuUnits`. Remove the now-unused imports `android.os.Build` and `androidx.annotation.RequiresApi` **only if nothing else in the file uses them** — check with `grep -n "Build\.\|RequiresApi" app/src/main/java/it/eldavo/ylih/ui/Format.kt` before deleting.

Update the class KDoc: the line *"Null on Android 6, the one release this ships to without `android.icu`"* is gone, and the `IcuUnits` KDoc's *"Every `android.icu` type in this file, behind one API check"* needs its second paragraph reworded — the class is now just where the ICU types live, not a version gate.

- [ ] **Step 2: Collapse `TrackingController.detailedTrackingSupported`**

Replace lines 71–77 with:

```kotlin
    fun detailedTrackingSupported(): Boolean =
        Distribution.HAS_SPECIAL_USE_FGS ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            hasBluetoothPermission()
```

and delete this paragraph from its KDoc, which is no longer true:

> Notification channels don't exist before Android 8 (API 26), and the service's whole point is its persistent notification, so detailed tracking is unavailable below that — those installs get Bluetooth-only tracking regardless of the setting.

- [ ] **Step 3: Make the notification channel unconditional**

In `Notifications.kt`, line 36 becomes an unguarded call and the `@RequiresApi(Build.VERSION_CODES.O)` on line 39 goes:

```kotlin
        ensureChannel(context)
```

Do not otherwise move this call. `CLAUDE.md` is emphatic that creating the channel at process start is load-bearing: created on the line above `startForeground`, it silently fails to appear when the notification permission is denied and `startForeground` then throws.

- [ ] **Step 4: Drop the remaining `@RequiresApi(O)`**

Delete `@RequiresApi(Build.VERSION_CODES.O)` from `TrackingService.kt:48` and `PlaybackWatcher.kt:22`, and the `androidx.annotation.RequiresApi` import from each if nothing else uses it.

- [ ] **Step 5: Fix two comments that are now wrong**

`AppLocale.kt:109-111` — the comment says `setLocale` is *"the form that exists at the API 23 floor"*. Keep the call; replace the comment with:

```kotlin
        // setLocale, not setLocales: from API 24 it stores a one-element locale list anyway,
        // and one locale is all this sets.
```

`Restrictions.kt:34` — *"on Android 6–10"* becomes *"on Android 8–10"*.

- [ ] **Step 6: Lint and test both flavors**

```bash
./gradlew lintClassicReleaseTest lintPlayReleaseTest
```

Expected: PASS. `ObsoleteSdkInt` is the check that fails if any guard was missed; its message names the file and line.

```bash
./gradlew testClassicReleaseTestUnitTest
```

Expected: FAIL, in the three `*LegacyTest` classes and one `FormatTest` case. That is the point of Task 6 and is not a regression — those tests assert behaviour that no longer ships.

- [ ] **Step 7: Commit**

```bash
git add app/src/main
git commit -m "Collapse the version guards Android 8 makes constant

Every SDK_INT >= N and >= O check in the app is now always true, and
lint's ObsoleteSdkInt would fail the build on each of them.

The largest is Format.kt, which carried a whole second implementation:
android.icu arrived in API 24, so below it every language got a
'%dh %02dm' with an English h and m that a screen reader read aloud.
That fallback and the nullable IcuUnits it hung on are gone.

detailedTrackingSupported() loses its first clause, which means the
Play build's revoked-Bluetooth route is now the only way to reach the
unavailable state. The tests that covered it through the old floor are
rewritten in the next commit."
```

---

## Task 6: Rewrite the tests whose subject the floor removed

**Files:**
- Modify: `app/src/test/java/it/eldavo/ylih/tracking/TrackingControllerLegacyTest.kt`
- Modify: `app/src/test/java/it/eldavo/ylih/ui/YlihViewModelLegacyTest.kt`
- Modify: `app/src/test/java/it/eldavo/ylih/ui/SettingsScreenLegacyTest.kt`
- Modify: `app/src/test/java/it/eldavo/ylih/widget/WidgetsLegacyTest.kt:34`
- Modify: `app/src/test/java/it/eldavo/ylih/ui/FormatTest.kt:70-81`
- Modify: `app/src/test/java/it/eldavo/ylih/ui/PermissionRationaleTest.kt:54`

**Interfaces:**
- Consumes: `TrackingController.detailedTrackingSupported()` and `Distribution.HAS_SPECIAL_USE_FGS` (a `const val Boolean`, `false` on play, `true` on classic).
- Produces: nothing later depends on.

**Background the implementer needs.** There are **no flavor-specific unit-test source sets** in this project — only `app/src/test`, shared by both flavors. The established way to write a flavor-dependent assertion is `DistributionTest`'s: assert *against* `Distribution.HAS_SPECIAL_USE_FGS` so one class is correct under both. Do not create `app/src/testPlay/`.

The three classes are being repointed from a route that no longer exists (API floor too old for notification channels) to the one that survives: **API 34 with `BLUETOOTH_CONNECT` denied**, which makes `detailedTrackingSupported()` false on `play` and true on `classic`. Robolectric denies runtime permissions by default, so *removing* the existing `grantPermissions` call is what produces the denied state; use `denyPermissions` explicitly where it reads more clearly.

- [ ] **Step 1: Repoint `SettingsScreenLegacyTest`**

Rename the file and class to `SettingsScreenUnsupportedTest`. Change the config to API 34 and invert the permission:

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
class SettingsScreenUnsupportedTest {
```

In `setUp`, replace the `grantPermissions` line with:

```kotlin
        // Denied, which is the one route left to the unavailable state: on Android 14+ the
        // connectedDevice service type requires a Bluetooth permission, and the play flavor
        // declares no specialUse type to fall back on. The classic build is never blocked,
        // which is what the assumption in each test is about.
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
```

Guard both tests so they run only on the flavor that can reach the state, using JUnit's `Assume`:

```kotlin
    @Test
    fun `without bluetooth access the screen says so instead of offering a dead switch`() {
        assumeFalse("only the play build can be blocked here", Distribution.HAS_SPECIAL_USE_FGS)
        show()

        assertEquals(1, nodeCount(text(R.string.settings_detailed_unavailable)))
        compose.onNode(
            isToggleable() and hasText(text(R.string.settings_detailed_title)),
        ).assertIsOff().assertIsNotEnabled()
    }

    @Test
    fun `and the playback choice is not offered at all`() {
        assumeFalse("only the play build can be blocked here", Distribution.HAS_SPECIAL_USE_FGS)
        show()

        assertEquals(0, nodeCount(text(R.string.settings_playback_only_title)))
    }
```

Add a third test so the classic flavor is not left asserting nothing in this file:

```kotlin
    @Test
    fun `a build with the special-use type is never blocked by a denied permission`() {
        assumeTrue("classic declares specialUse", Distribution.HAS_SPECIAL_USE_FGS)
        show()

        assertEquals(0, nodeCount(text(R.string.settings_detailed_unavailable)))
        compose.onNode(
            isToggleable() and hasText(text(R.string.settings_detailed_title)),
        ).assertIsEnabled()
    }
```

Imports to add: `it.eldavo.ylih.Distribution`, `org.junit.Assume.assumeFalse`, `org.junit.Assume.assumeTrue`, `androidx.compose.ui.test.assertIsEnabled`.

Rewrite the class KDoc. The old one describes the API 23 floor; the new subject is the Bluetooth route and the flavor split.

- [ ] **Step 2: Repoint `YlihViewModelLegacyTest`**

Rename to `YlihViewModelUnsupportedTest`, set `@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])`, replace `grantPermissions` with `denyPermissions`, and guard the single test with `assumeFalse(..., Distribution.HAS_SPECIAL_USE_FGS)`.

The assertion body does not change at all — it already expects `R.string.detailed_needs_bluetooth`, which was always the Bluetooth wording:

```kotlin
    @Test
    fun `a build that cannot run the service says so rather than leaving the user guessing`() = runTest {
        assumeFalse("only the play build can be blocked here", Distribution.HAS_SPECIAL_USE_FGS)
        app.container.settings.setDetailedTracking(false)

        viewModel.setDetailedTracking(true).join()

        assertFalse(viewModel.detailedTrackingSupported.value)
        assertEquals(
            app.getString(R.string.detailed_needs_bluetooth),
            viewModel.messages.first(),
        )
        assertFalse("and nothing was written", app.container.settings.detailedTrackingNow())
    }
```

- [ ] **Step 3: Repoint `TrackingControllerLegacyTest`**

Rename to `TrackingControllerUnsupportedTest`, set `@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE])`, and replace the `grantPermissions` line in `setUp` with `denyPermissions`.

Guard the first three tests with `assumeFalse("only the play build can be blocked here", Distribution.HAS_SPECIAL_USE_FGS)`:

- `without notification channels there is no service to offer` — rename to `without bluetooth access there is no service to offer`
- `asking for detailed tracking here is refused rather than half-applied`
- `a setting that arrived from a newer phone falls back instead of counting forever`

Leave the fourth test, `bluetooth-only tracking still works down here`, **unguarded** — it asserts the Bluetooth path works and is true on both flavors. Rename it to `bluetooth-only tracking still works without the permission`.

- [ ] **Step 4: Retarget `WidgetsLegacyTest`**

This class survives: its subject is the API < 31 dynamic-colour fallback, which minSdk 26 does not reach. Change line 34 only:

```kotlin
@Config(sdk = [Build.VERSION_CODES.O])
```

and in its KDoc, *"on everything from the minSdk 23 floor up to 11"* becomes *"on everything from the Android 8 floor up to 11"*.

- [ ] **Step 5: Delete the `FormatTest` case whose subject is gone**

Delete the whole test at `FormatTest.kt:70-81`, including its KDoc:

```kotlin
    /**
     * Android 6 has no `android.icu`, and still has to print something. ...
     */
    @Test
    @Config(sdk = [Build.VERSION_CODES.M])
    fun `below android 7 the units fall back to the ones every language used to get`() { ... }
```

- [ ] **Step 6: Retarget `PermissionRationaleTest`**

Line 54: `@Config(sdk = [Build.VERSION_CODES.M, Build.VERSION_CODES.S_V2])` becomes:

```kotlin
    @Config(sdk = [Build.VERSION_CODES.O, Build.VERSION_CODES.S_V2])
```

- [ ] **Step 7: Run the suite on both flavors**

```bash
./gradlew testClassicReleaseTestUnitTest testPlayReleaseTestUnitTest
```

Expected: PASS on both. A `Assume` failure is reported as a *skipped* test, not a failure — confirm with the HTML report at `app/build/reports/tests/` that the play run skips the classic-only test and vice versa, rather than everything skipping (which would mean `Distribution.HAS_SPECIAL_USE_FGS` was read wrongly).

- [ ] **Step 8: Commit**

```bash
git add app/src/test
git commit -m "Repoint the floor tests at the route that still exists

Three classes existed to pin what an install too old for notification
channels does: the controller refuses detailed tracking, the view model
says so out loud rather than springing the switch back, and the settings
screen shows the note and hides the playback choice. Android 8 as a floor
means no install can be that old any more.

The state itself still ships — the play build reaches it on Android 14+
with Bluetooth denied, because it declares no specialUse type — so the
tests move there rather than being deleted, guarded by
Distribution.HAS_SPECIAL_USE_FGS the way DistributionTest already is.
That does mean the branch is now covered on one flavor rather than both,
which is the flavor split CLAUDE.md describes; CI runs both."
```

---

## Task 7: Move the emulator floor leg and fix the docs that describe the old one

**Files:**
- Modify: `.github/workflows/android-ci.yml` (the `instrumented` matrix, around line 211)
- Modify: `docs/fdroid.md:40-44`
- Modify: `CLAUDE.md`
- Modify: `app/lint.xml:42`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

**The check name does not change.** It is built from `matrix.label`, which stays `minified, minSdk`, so the required status check contexts in the `main protection` ruleset are unaffected. **No ruleset edit is needed** — do not make one.

- [ ] **Step 1: Move the floor leg to API 26**

In the `instrumented` matrix, replace the third entry's comment and API level:

```yaml
          # And once at the minSdk floor, which is the only place the app's own API-26 branches
          # run on a device: the unit tests cannot, because Robolectric runs against the compile
          # SDK, which is why lint.xml exempts src/test from NewApi.
          - buildType: releaseTest
            variant: ReleaseTest
            apiLevel: 26
            label: minified, minSdk
```

The old comment justified this leg by core library desugaring — *"Everything java.time in Stats.kt and Format.kt is backported there ... and nothing else in CI ever runs that code on a runtime that desugars it"*. After Task 4 there is no desugaring, so that reason is gone and the leg stands on being the floor.

- [ ] **Step 2: Confirm an API 26 emulator image is actually available**

Before relying on this leg, check that `reactivecircus/android-emulator-runner` can start one. The cheapest proof is the CI run on this PR: if the leg fails during image download rather than during the tests, raise the floor leg to the lowest API the runner supports at or above 26 and say so in the comment. Do not silently delete the leg.

- [ ] **Step 3: Remove the desugaring licence paragraph from `docs/fdroid.md`**

Delete the whole `com.android.tools:desugar_jdk_libs` bullet (lines 40–44). It is the paragraph arguing that a **GPL-2.0-with-Classpath-Exception** artifact in the APK does not reach the app's MIT licence. With the dependency gone the argument has no subject, and leaving it would tell an F-Droid reviewer to look for something that is not there.

- [ ] **Step 4: Update `CLAUDE.md`**

Four places refer to the old floor or to desugaring:

- line ~60 — *"Robolectric runs against the compile SDK rather than the minSdk 23 floor those checks enforce"* → `minSdk 26`
- line ~118 — *"the API 23 floor, say, where no build can run the foreground service"*. This example is now **wrong**, not just stale: every build can run the foreground service. Replace it with the route that survives — the Play flavor on Android 14+ with Bluetooth denied — and note that the `*LegacyTest` classes were renamed to `*UnsupportedTest`.
- the Testing section's paragraph on `app/build.gradle.kts` pinning the `releaseTest` L8 runs to the unshrunk desugared library — delete it; there is no L8 run left.
- the Build-system constraints section, if it names `desugar_jdk_libs`.

Search for anything missed:

```bash
grep -rn "desugar\|minSdk 23\|API 23\|L8\|j\$" CLAUDE.md docs/ README.md
```

Expected after the edits: no hits describing current behaviour.

- [ ] **Step 5: Update the `app/lint.xml` comment**

Line 42 says *"the minSdk 23 floor these checks enforce does not apply to them"*. The exemption stays valid — Robolectric still runs against the compile SDK — only the number changes, to 26.

- [ ] **Step 6: Lint the workflow and commit**

```bash
nix shell nixpkgs#actionlint -c actionlint
git add .github/workflows/android-ci.yml docs/fdroid.md CLAUDE.md app/lint.xml
git commit -m "Point the floor leg and the docs at Android 8

The minSdk emulator leg justified itself by core library desugaring —
it was the only place in CI that ran java.time on a runtime that
desugared it. There is no desugaring now, so the leg stands on being
the floor instead, and docs/fdroid.md loses the paragraph arguing that
a GPL-with-Classpath-Exception artifact in the APK does not reach the
app's MIT licence, there being no such artifact any more.

CLAUDE.md's example of a path reachable without the flavor split was
the API 23 floor where no build can run the foreground service. That is
now false rather than merely stale, and the surviving example is the
play build on Android 14+ with Bluetooth denied."
```

---

## Task 8: Take the pins off and regenerate the dependency checksums

**Files:**
- Modify: `gradle/libs.versions.toml` (`material3`, `navigationCompose`)
- Modify: `.github/dependabot.yml` (the ignore entry)
- Modify: `gradle/verification-metadata.xml` (regenerated, not hand-edited)

**Interfaces:**
- Consumes: `minSdk = 26` from Task 4.
- Produces: nothing later depends on.

- [ ] **Step 1: Bump navigation**

In `gradle/libs.versions.toml`:

```toml
navigationCompose = "2.10.0"
```

This is the bump PR #21 was carrying.

- [ ] **Step 2: Take material3 off its hand-held pin**

Replace the pin comment. The paragraph beginning *"Held below the latest alpha (1.5.0-alpha26) on purpose: alpha26 pulls in material3-ripple-android, which raises minSdk to 24..."* and its two following sentences about `.github/dependabot.yml` are deleted. The paragraph above it — the one explaining why material3 is ahead of the Compose BOM — **stays**, because that reason is unchanged.

Then set `material3` to the newest release that resolves, and record which one in the commit message.

- [ ] **Step 3: Remove the Dependabot ignore**

In `.github/dependabot.yml`, delete the whole `ignore:` block for the gradle ecosystem — the six-line comment and the `- dependency-name: "androidx.compose.material3:material3"` entry. It exists only because of the minSdk floor. If that leaves the `gradle` entry with no other keys between `groups:` and the next ecosystem, check the YAML still parses:

```bash
python3 -c "import yaml,sys; yaml.safe_load(open('.github/dependabot.yml')); print('ok')"
```

- [ ] **Step 4: Regenerate the verification metadata**

This is the step with the trap. Read the Build-system constraints section of `CLAUDE.md` before running it. `GRADLE_OPTS` **must** be cleared: `flake.nix` points `android.aapt2FromMavenOverride` at the SDK's own patched `aapt2`, so a build under the override never resolves `com.android.tools.build:aapt2` and the metadata written here would be complete on this machine and two artifacts short everywhere else. The F-Droid buildserver found that the hard way.

```bash
GRADLE_OPTS= ./gradlew --write-verification-metadata sha256 --refresh-dependencies \
    lintClassicReleaseTest createClassicReleaseTestUnitTestCoverageReport \
    testClassicDebugUnitTest assembleClassicDebug assembleClassicRelease \
    assembleClassicReleaseTestAndroidTest \
    lintPlayReleaseTest createPlayReleaseTestUnitTestCoverageReport \
    testPlayDebugUnitTest assemblePlayDebug assemblePlayRelease \
    assemblePlayReleaseTestAndroidTest
```

The full task set, not a subset: a configuration that was not resolved contributes no checksums. `--refresh-dependencies` is not optional: a cache-resident artifact is not re-resolved and its checksum is silently left out.

- [ ] **Step 5: Prove it, because generation and verification are different code paths**

```bash
GRADLE_OPTS= ./gradlew --refresh-dependencies assembleClassicRelease
```

Expected: PASS. A `Dependency verification failed ... checksums are missing` here means step 4's task list was short.

- [ ] **Step 6: Review the diff before committing it**

```bash
git diff --stat gradle/verification-metadata.xml
```

The trust moves from the checksum to whoever reads this diff. Confirm the added entries are the ones the version bumps explain — navigation 2.10.0, the material3 release chosen, and their transitive dependencies — and nothing else.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml gradle/verification-metadata.xml .github/dependabot.yml
git commit -m "Take the two pins the old floor forced

navigation 2.10.0 declares minSdk 24 and material3 past 1.5.0-alpha25
pulls in a material3-ripple-android that declares the same, so both were
held by hand against a floor of 23. The floor is 26 now and neither
needs holding.

The dependabot.yml ignore goes with the material3 pin. It was there
because a grouped PR is one merge or none, so the unmergeable bump took
every healthy one in the group down with it every Monday — which is
exactly what PR #21 did with four other updates."
```

---

## Task 9: Measure coverage against the floors and report

**Files:**
- None modified. This task produces a number and a decision.

**Interfaces:**
- Consumes: everything above.
- Produces: either a green PR C or a finding to bring back.

**This task exists because Task 6 changed which flavor covers a branch,** and the coverage gate is a hard CI gate at `--min-instruction=95 --min-line=99 --min-branch=70`, with classic previously at 96.3 / 99.1 / 77.1.

- [ ] **Step 1: Measure classic**

```bash
./gradlew createClassicReleaseTestUnitTestCoverageReport
python3 .github/scripts/coverage-summary.py \
    app/build/reports/coverage/test/classic/releaseTest/report.xml classic
```

- [ ] **Step 2: Measure play**

```bash
./gradlew createPlayReleaseTestUnitTestCoverageReport
python3 .github/scripts/coverage-summary.py \
    app/build/reports/coverage/test/play/releaseTest/report.xml play
```

- [ ] **Step 3: Compare and decide**

Expected: instruction and line at or above 96.3 / 99.1 on classic. Deleting `Format.kt`'s `legacy()` removes uncovered-adjacent code, so both should rise slightly. Branch coverage may move either way.

**If any figure is below its floor, stop.** Do not lower a floor, do not add a lint baseline, do not delete an assertion. The honest outcomes are: write a test that covers the branch on the flavor that can reach it, or report the number and the reason to the repository owner and let them decide. This is the rule the agent prompts in this repository already enforce, and this plan is held to it.

- [ ] **Step 4: Run the full CI task set locally, both flavors**

```bash
./gradlew lintClassicReleaseTest createClassicReleaseTestUnitTestCoverageReport \
          assembleClassicDebug assembleClassicRelease
./gradlew lintPlayReleaseTest createPlayReleaseTestUnitTestCoverageReport \
          assemblePlayDebug assemblePlayRelease
```

- [ ] **Step 5: Check the R8 keep invariants still hold**

```bash
python3 .github/scripts/r8-keep-check.py app/build/outputs/mapping/classicRelease
```

Expected: PASS. Removing the desugared library changes what R8 sees; this is the cheap proof nothing reachable only by name was stripped or renamed.

- [ ] **Step 6: Open PR C and let the emulator legs run**

The instrumented suite is the half no local check replaces — it installs the minified APK and runs it. It is also the only thing that proves the API 26 floor leg works and that removing the L8 pinning did not break the two-APK arrangement `app/src/releaseTest/keepRules/instrumented-test.keep` describes.

Expected: all three `instrumented` legs green. A failure on the minified leg but not the unminified one means R8; `CLAUDE.md`'s Testing section says what that usually is.

---

## Self-Review

**Spec coverage.** Change 1 → Task 1. Change 2 → Tasks 2 and 3, with the `dependabot-auto-merge.yml` edit the spec flagged as required broken out as its own task so it cannot be forgotten. Change 3 → Tasks 4–9: build (4), app code (5), tests (6), CI and docs (7), pins and checksums (8), coverage (9). The spec's two verify-empirically risks — the extra-approval flag and the `workflow_run` secret store — are steps 4 and 5 of Tasks 1 and 3 rather than left as prose.

**Type consistency.** `detailedTrackingSupported()` keeps its signature across Tasks 5 and 6. `Distribution.HAS_SPECIAL_USE_FGS` is used in Task 6 exactly as `DistributionTest` already uses it. `Formatters.duration`/`hours` keep their signatures in Task 5, so `FormatTest`'s surviving cases need no edit.

**Known soft spots, flagged rather than hidden.** Task 7 step 2 (API 26 emulator image availability) and Task 8 step 2 (which material3 release actually resolves) cannot be settled without running them; both say so and neither is written as an assumption. Task 3 step 3 is a reading check on the three-way give-up condition rather than a run, because two of its three branches cannot be provoked on demand.

**One design change made during review.** Task 3 originally recorded a stalled Claude run with `.github/actions/agent-stall`, matching the issue pipeline. That is wrong here and would have been invisible: `agent-retry.yml` sweeps with `gh issue list`, which excludes pull requests, so the label would have been written and never read — the branch would sit red, armed and silent, which is the exact state `agent-stall` exists to prevent. A stalled run now takes the same visible exit as a failed one.
