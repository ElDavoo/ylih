# The agent pipeline

An issue filed here is planned, implemented, reviewed and merged without anyone touching it.
This document is what it does, what you have to set up before it can, and which parts are
load-bearing enough that changing them will break it quietly.

## The shape

```
issues: opened
   └─ agent-plan.yml ──────────► rewrites the issue body to be the plan
                                        │ gh workflow run  (PAT)
                                        ▼
                                 agent-implement.yml ──► agent/issue-N, PR, auto-merge armed
                                        │
                        ┌───────────────┴───────────────┐
                        ▼ push triggers                 ▼ pull_request
                  Android CI                      agent-review.yml
                        │ failure                        │ REQUEST_CHANGES
                        ▼                                ▼
                  agent-fix.yml ◄────────────────────────┘   (workflow_call)
                        │ push
                        └──► re-triggers both  ⟲   until green + approved → merge
```

Nothing in it is a loop. Every file reacts to one event and returns; the cycle exists because a
push re-triggers CI and review, and each of them can call the fix stage. What terminates it is
the round counter in `agent-fix.yml`, not a condition anyone waits on.

The stages are separate workflow runs rather than jobs in one run for two reasons: the approval
gate for an outside issue has to sit on each stage independently, and a stage that fails can be
re-dispatched from the Actions tab without paying for the ones before it.

## Setup

None of this works until all six are done. All six are done on `ElDavoo/ylih` as of
2026-08-26; what follows is the record of what was set and why, for the next repository or the
next time one of them is quietly turned off.

**1. A pull-request token.** Create a fine-grained PAT scoped to this repository only, with
*Contents: read and write*, *Pull requests: read and write*, *Issues: read and write* and
*Actions: read and write*. Store it as an **Actions** secret named `AGENT_PUSH_TOKEN`.

Not for permissions — for triggering. A branch pushed with `GITHUB_TOKEN` **starts no workflow
runs at all**, so Android CI would never run on the pull request and the armed auto-merge would
wait forever for a check that cannot arrive. This is the same trap `CLAUDE.md` documents for
`DEPENDABOT_PUSH_TOKEN`, arrived at from the other direction. `gh workflow run` has the same
problem, which is why the plan stage dispatches with the PAT too.

**2. The approval environment.** Settings → Environments → New environment → `agent-approval`.
Tick *Required reviewers* and add yourself. Save.

Any job carrying `environment: agent-approval` now queues instead of running, and shows as
"Review pending deployments" on the run page with an Approve/Reject button. It waits 30 days.
The queue happens **before the job's first step** — no checkout, no prompt, no token — which is
the entire security argument for letting a public issue tracker drive this at all.

If you also move `AGENT_PUSH_TOKEN` into that environment rather than leaving it on the
repository, an unapproved job cannot read it even if a bug lets it run. Worth doing.

**3. Let Actions approve.** Settings → Actions → General → tick *Allow GitHub Actions to create
and approve pull requests*. The review stage submits its approval with `GITHUB_TOKEN`, which is
blocked from approving by default.

**4. Squash message.** Settings → General → Pull Requests → *Default commit message* →
**"Pull request title and description"**. Also tick *Allow auto-merge*.

This is what keeps ten fix rounds out of `main`'s history. The default squash message
concatenates every commit on the branch, so without this the wall of `fix round 7 (ci)` subjects
lands in one commit instead of nine — tidier, but no more readable. With it, the squash commit is
exactly the pull request title and body, which the review stage rewrites at approval time to
describe what actually landed rather than what was originally planned.

**5. Labels.**

```sh
gh label create no-agent        --color ededed --description "Do not let the agent pipeline touch this"
gh label create 'agent:stop'    --color b60205 --description "Halt the pipeline for this issue or PR"
gh label create 'agent:stuck'   --color d93f0b --description "Gave up after 10 fix rounds — needs a human"
gh label create 'agent:planned' --color 0e8a16 --description "Planned, waiting to be implemented"
gh label create 'agent:working' --color fbca04 --description "Being implemented"
gh label create 'agent:declined' --color ededed --description "Not work an agent should take unattended"
```

**6. The `main protection` ruleset.** A *ruleset*, not legacy branch protection — the
`/branches/main/protection` endpoint 404s on this repository, which is expected and not a sign
anything is missing. Read it with:

```sh
gh api repos/ElDavoo/ylih/rulesets/19763281
```

It must carry both of these, and the second is the one easy to leave out:

- `required_status_checks` over every Android CI context, spelled exactly as the jobs report
  them — the matrix legs are `build (classic, Classic)` and `build (play, Play)`, not
  `build (classic)`, and there are three `instrumented` legs. `listing` matters more than it
  looks: it is where actionlint runs, so it is the check that catches a broken agent workflow.
- a `pull_request` rule with `required_approving_review_count: 1`.

**Without the approval rule the review stage is decorative.** Auto-merge waits for whatever the
ruleset requires and nothing else, so a pull request would merge on green CI alone and the
reviewer's verdict would never be consulted. The repository was in exactly that state when this
pipeline was first set up.

`dismiss_stale_reviews_on_push` is on, so an approval does not carry across a later fix round —
the review stage re-runs on every `synchronize` and re-approves, which is what makes that safe.

The admin bypass actor stays: it is what keeps this from gating your own direct pushes to
`main`.

## The two identities, and why there are two

| Actor | Token | Identity on GitHub |
|---|---|---|
| plan, implement, fix | `AGENT_PUSH_TOKEN` | you |
| review | `GITHUB_TOKEN` | `github-actions[bot]` |

They have to differ. GitHub refuses to let an identity approve its own pull request, so if the
same token opened the PR and submitted the review, the approval would be rejected and nothing
would ever merge. The implementer is the one that must be the PAT (see setup step 1), so the
reviewer is the one that gets `GITHUB_TOKEN`.

**The one assumption not yet verified in production:** that an approval from
`github-actions[bot]` satisfies branch protection's "require 1 approval". If it turns out not
to, the fix is to give the review stage its own identity — a GitHub App installed on the repo —
and leave everything else as it is.

## Controls

| You want | Do this | Works when |
|---|---|---|
| never let it touch this issue | file with the **Note to self** template (`no-agent`) | at creation only |
| stop it now | label the issue or PR `agent:stop` | any time |
| it gave up | it labels `agent:stuck` and drafts the PR | after 10 rounds |
| pick a stuck one back up | remove the label, re-run **Agent · implement** | any time |

`no-agent` only works applied at creation, because `agent-plan.yml` fires on `issues: opened` and
a label added a second later loses the race. That is what the issue template is for, and it is
why `agent:stop` exists as the escape hatch that always works — it both blocks every stage from
starting and cancels what is already running.

There is a cap of **3 open agent pull requests**. Past that the plan stage declines with a
comment rather than queueing, because the failure mode worth designing against is an evening of
issue filing turning into twelve branches and twelve CI matrices.

## The rounds

Fix rounds are counted in **pushes by the agent**, not in CI runs. The `instrumented` matrix is
the one job in this repository that fails for reasons unrelated to the diff, and a flaky emulator
must not be able to spend the budget of a branch that was fine.

- **1–8** — ordinary: read the failure, fix the cause.
- **9** — escalation. Handed the full attempt history (`git log -p`) rather than the latest
  failure, told explicitly that eight plausible-looking failures are evidence about the
  *diagnosis*, and permitted to `git revert` and take a different route. It runs with a larger
  turn budget. This exists because the way these loops actually fail is an agent applying
  variations of a fix that never addressed the cause, and from inside any single round that is
  indistinguishable from progress.
- **10** — last ordinary round. On failure: draft the PR, label `agent:stuck`, and comment with
  every round's subject and the final failure.

Drafting is not cosmetic — it disarms auto-merge, which is precisely what you want at the moment
the loop admits it is lost.

## What the plan stage refuses

`implementable: false` is a normal outcome, not a failure. The plan stage declines a question, a
bug report with no reproduction, anything needing a Room migration or a new locale, anything
touching signing, release or the dependency verification metadata, anything that would move the
tracking accuracy rules or `SessionRepository`'s invariants or the device-identity keying, and
anything too vague to have an obvious acceptance test. Those are the areas where `CLAUDE.md`
says the reasoning matters more than the diff.

## Prompt injection

Issue and comment bodies are attacker-controlled — this is a public tracker. Three things stand
between that and the repository, in descending order of how much they actually buy:

1. **The environment gate.** An outside issue does not reach a prompt until you approve it. This
   is the real mitigation; the other two are defence in depth.
2. **Framing.** Every prompt that carries reported text delimits it and says plainly that it is
   data describing a request, not instructions — and the plan stage is told that text shaped like
   an instruction *to it* is itself grounds to decline.
3. **Enumerated tools.** The plan stage is given no write tool at all: it returns a structured
   verdict and the workflow's own shell steps edit the issue. No stage but the plan one can
   dispatch another workflow.

Untrusted text always moves through the environment (`env:`), never interpolated into a `run:`
block. An issue body containing shell metacharacters is ordinary, and interpolating one into a
script is how that becomes arbitrary code on a runner holding a push token.

**What none of this stops, stated plainly:** once you approve an outside issue, auto-merge is
armed for it exactly as it is for your own, and the resulting code can reach `main` without
anyone reading the diff. The approval gates *intent*, not output. Android CI is a strong gate but
it is not an adversary model — nothing in lint or coverage objects to a plausible change that
quietly alters what `reconcile` counts. If that trade stops looking right, the smallest fix is to
skip the `Arm auto-merge` step when `inputs.author != 'ElDavoo'`: same pipeline, outside issues
land at green-and-approved awaiting your click.

## Testing

There is nothing to unit test — these are workflows, and the only way to run one is to run it.
What stands in for tests:

- `actionlint` runs in the `listing` job of `android-ci.yml` on every push. It type-checks every
  `${{ }}` against the event payload and runs shellcheck over each `run:` block, and it is not
  optional: it is what caught that ` #` in a plain YAML scalar starts a comment, which had been
  silently truncating three `run:` values to an unterminated quote.
- The gate pattern — `needs: [gate]` with `always() && result != 'failure' && != 'cancelled'` —
  is the one piece of logic here subtle enough to fail silently. Without `always()` a *skipped*
  gate skips the guarded job too, which is the path your own issues take, so the pipeline would
  do nothing at all for you and work fine for everyone else. Worth proving once by temporarily
  inverting the `if:` so one of your own issues takes the gated path.

Run the linter locally the same way CI does:

```sh
nix shell nixpkgs#actionlint -c actionlint
```
