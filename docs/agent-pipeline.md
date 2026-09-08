# The agent pipeline

An issue filed here is planned, implemented, reviewed and merged without anyone touching it. This
document covers what it does, what to set up first, and which parts break quietly if changed.

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

any stage dying mid-run ──► agent:stalled ──► agent-retry.yml (every 5 h) ──► re-runs it
a queued turn displaced ──► agent:planned, no PR ──► agent-retry.yml ──► dispatches implement
agent:stop on issue or PR ──► agent-stop.yml ──► cancels runs, drafts the PR
```

Nothing here loops. Every file reacts to one event and returns; a push re-triggers CI and review,
and either can call the fix stage. The round counter in `agent-fix.yml` terminates the cycle, not
a waited-on condition.

Stages are separate workflow runs, not jobs in one run: the approval gate for an outside issue
sits on each stage independently, and a failed stage can be re-dispatched from the Actions tab
without repaying the ones before it.

## One agent at a time

Every stage that runs Claude names the same concurrency group, `agent-pipeline`, and none cancels
in progress: plan, implement, review and both fix stages queue behind one another across issues as
much as within one, so two agents never run at once no matter how many issues are open.

**A token budget, not a correctness rule.** The subscription window is about five hours; three runs
sharing it spend it three times as fast without getting further — here every stage hit its turn
limit at once, buying several half-finished branches instead of one merged PR. Serialised, the same
window pays for runs that finish. Turn limits doubled with it (implement 300, fix 240, escalation
400, Dependabot fix 240), every stage pinned to `--model opus --effort medium` — sensible only once
the window isn't split.

Job timeouts moved with the turn limits (60 to 120 minutes wherever the budget doubled), and the
pairing matters: a job the timeout kills skips every remaining step, *including* the stall record,
so the retry sweep never learns the stage stopped and the issue sits looking planned and idle. A
turn limit reached gets retried; a wall-clock timeout does not.

Two consequences:

**`agent-fix.yml` is not in the group.** As a reusable workflow it runs inside its caller's run,
which already holds `agent-pipeline`; asking for the same group from a job inside that run would
queue behind a slot its own parent holds and won't release — a deadlock until the run times out.
Serialising the fix stage is the caller's job; the per-issue group only has to hold its two
callers off each other. So `agent-fix-ci.yml` declares the group at *workflow* level, not on the
job that calls the fix stage.

**GitHub's queue depth for a group is one.** A group holds one run in flight and one pending; a
*third* arrival cancels the pending one before its first step rather than lining up behind
it — nothing it would have written gets written. Filing issues a few minutes apart avoids it. When
it happens:

- **implement** recovers: it swaps `agent:planned` for `agent:working` only once its PR exists, so
  `agent:planned` with no branch signals a dropped turn — what `agent-retry.yml`'s second sweep
  looks for, turning the drop into a queue.
- **plan** does not: a plan that never ran leaves nothing to sweep for, so a displaced one needs a
  manual re-run from the Actions tab.

**A run whose jobs all skip still queues for the group.** `agent-fix-ci.yml` holds the group from
its first job (it must — the stage it calls is a reusable workflow running inside this run), and
`workflow_run` fires it for every Android CI run, including pushes to `main`; a run queues before
its jobs' conditions are evaluated, and only then skips. Run 33992872153 queued behind the
implement stage with `head_branch` `main` and nothing to do — long enough to displace a pending
`Agent · review`, whose check then never reported. It now filters on `branches: ['agent/issue-*']`
at the trigger, so no run is created at all; since the trigger can't filter on conclusion, a
*green* CI run on an agent branch still arrives and skips, rare and only mid-work.

`Claude Code Review` joined the group too and stopped running on agent branches at the same time.
Agent PRs are opened with `AGENT_PUSH_TOKEN`, so their author is you — that workflow used to fire
alongside `Agent · review`, two Claude reviewers on one diff for one verdict, and since both run
the same plugin, dropping the second copy lost nothing. Under a serial group it cost more than
tokens: two arrivals per push routinely displaced the waiting one, half the time the merge gate,
leaving auto-merge waiting forever.

**Excluding it in the job's `if:` did not stop it queueing** — found the same way. Runs 33997122850
and 34009431987, both on `agent/issue-32`, ended `cancelled` rather than `skipped`; the same
workflow on a Dependabot branch skipped in five seconds, reaching a free slot instead of a pending
one. `agent-fix-ci.yml`'s fix doesn't transfer, since `pull_request`'s `branches:` filter matches
the **base** branch and every agent PR targets `main`, leaving no head-branch filter to write.
Instead: move `concurrency:` off the workflow onto the job, since a job skipped by its `if:` never
asks for the group — which only works for a workflow calling no reusable workflow, the same
constraint that pins the group at workflow level in `agent-fix-ci.yml`, so the two files sit on
opposite sides of it. **A run ending `cancelled` where you expected `skipped` is this.**

**The review's verdict pass runs before its inline pass, on purpose.** The two are
independent — the verdict re-reads the diff, not the comments — but the verdict fails the run, and
a failed run discards what the inline pass already spent. Run 33997123035: 6m43s on the inline
pass, which posted nothing, then the verdict lost in ten seconds, and the retry re-ran both from
the top; in this order a stall costs ten seconds of the window. `Capture the findings for the fix
stage` reads inline comments back from the API and runs after both, indifferent to order.

## Setup

None of this works until all six are done — as of 2026-08-26 they are, on `ElDavoo/ylih`. Below is
what was set and why, for the next repository or the next time one is quietly turned off.

**1. A pull-request token.** Create a fine-grained PAT scoped to this repository only, with
*Contents: read and write*, *Pull requests: read and write*, *Issues: read and write* and
*Actions: read and write*. Store it as an **Actions** secret named `AGENT_PUSH_TOKEN`.

Not for permissions but triggering: `GITHUB_TOKEN` **starts no workflow runs at all**, so Android
CI would never run on the pull request and the armed auto-merge would wait forever for a check
that can't arrive — the same trap `CLAUDE.md` documents for `DEPENDABOT_PUSH_TOKEN`, from the other
direction. `gh workflow run` has the same problem, so the plan stage dispatches with the PAT too.

**2. The approval environment.** Settings → Environments → New environment → `agent-approval`.
Tick *Required reviewers* and add yourself. Save.

Any job carrying `environment: agent-approval` now queues instead of running, shown as "Review
pending deployments" on the run page with an Approve/Reject button, and waits 30 days. The queue
happens **before the job's first step** — no checkout, no prompt, no token — the whole security
argument for letting a public issue tracker drive this at all.

**`AGENT_PUSH_TOKEN` must be a repository secret, not a secret on this environment.** The tempting
hardening — scoping the token to `agent-approval` so an unapproved job can't read it — fails
silently: an environment secret is readable only by a job declaring that environment, and that job
is the *gate*, which does nothing but wait. Jobs using the token carry no `environment:` key by
design, so your own issues skip the wait — meaning a token stored on the environment resolves to an
empty string wherever needed, and the pipeline fails at `actions/checkout` for everybody. That was
the repository's actual state on day one, and the failure looks nothing like a permissions problem.

Nothing is lost keeping it at repository level: the security property that matters is ordering —
no runner starts before you approve — which comes from the gate job, not from where the secret
lives.

**3. Let Actions approve.** Settings → Actions → General → tick *Allow GitHub Actions to create
and approve pull requests*. The review stage submits its approval with `GITHUB_TOKEN`, blocked
from approving by default.

**4. Squash message.** Settings → General → Pull Requests → *Default commit message* →
**"Pull request title and description"**. Also tick *Allow auto-merge*.

This keeps ten fix rounds out of `main`'s history. The default squash message concatenates every
commit on the branch, so without it a wall of `fix round 7 (ci)` subjects lands in one commit
instead of nine. With it, the squash commit is exactly the PR title and body, which the review
stage rewrites at approval time to describe what landed rather than what was planned.

**5. Labels.**

```sh
gh label create 'agent:stalled' --color d4c5f9 --description "A stage stopped before finishing — agent-retry.yml will re-run it"
gh label create no-agent        --color ededed --description "Do not let the agent pipeline touch this"
gh label create 'agent:stop'    --color b60205 --description "Halt the pipeline for this issue or PR"
gh label create 'agent:stuck'   --color d93f0b --description "Gave up after 10 fix rounds — needs a human"
gh label create 'agent:planned' --color 0e8a16 --description "Planned, waiting to be implemented"
gh label create 'agent:working' --color fbca04 --description "Being implemented"
```

**6. The `main protection` ruleset.** A *ruleset*, not legacy branch protection — the
`/branches/main/protection` endpoint 404s here, as expected. Read it with:

```sh
gh api repos/ElDavoo/ylih/rulesets/19763281
```

It must carry both of these, the second easy to leave out:

- `required_status_checks` over every Android CI context, spelled exactly as the jobs report
  them — matrix legs are `build (classic, Classic)` and `build (play, Play)`, not
  `build (classic)`, plus three `instrumented` legs. `listing` matters most: actionlint runs
  there, so it's the check that catches a broken agent workflow.
- a `pull_request` rule with `required_approving_review_count: 1`.

**Without the approval rule the review stage is decorative:** auto-merge waits only for whatever
the ruleset requires, so a PR would merge on green CI alone with the reviewer's verdict never
consulted — the repository's actual state at first.

`dismiss_stale_reviews_on_push` is on, so an approval doesn't carry across a later fix round; the
review stage re-runs and re-approves on every `synchronize`, which makes that safe.

The admin bypass actor stays, so this doesn't gate your own direct pushes to `main`.

## The two identities, and why there are two

| Actor | Token | Identity on GitHub |
|---|---|---|
| plan, implement, fix | `AGENT_PUSH_TOKEN` | you |
| review | `GITHUB_TOKEN` | `github-actions[bot]` |

They have to differ: GitHub refuses to let an identity approve its own pull request, so if one
token both opened the PR and submitted the review, the approval would be rejected and nothing
would merge. The implementer must be the PAT (setup step 1), so the reviewer gets `GITHUB_TOKEN`.

**Not yet verified in production:** that an approval from `github-actions[bot]` satisfies branch
protection's "require 1 approval". If not, the fix is giving the review stage its own identity —
a GitHub App installed on the repo — and leaving everything else as is.

## Controls

| You want | Do this | Works when |
|---|---|---|
| never let it touch this issue | file with the **Note to self** template (`no-agent`) | at creation only |
| stop it now | label the issue or PR `agent:stop` | any time |
| a stage died mid-run | it labels `agent:stalled`; `agent-retry.yml` re-runs it | within 5 hours |
| a stage never got its turn | `agent-retry.yml` starts it once the queue is idle | within 5 hours |
| it gave up | it labels `agent:stuck` and drafts the PR | after 10 rounds, or 3 stalls |
| pick a stuck one back up | remove the label, re-run **Agent · implement** | any time |
| jump the queue | run **Agent · retry** by hand from the Actions tab | when nothing is running |

`no-agent` only works applied at creation: `agent-plan.yml` fires on `issues: opened`, and a label
added a second later loses the race — hence the issue template, and why `agent:stop` is the escape
hatch that always works, blocking every stage from starting and cancelling whatever's running.

There's a cap of **3 open agent pull requests**; past that the plan stage declines with a comment
rather than queueing, guarding against an evening of issue filing turning into twelve branches and
twelve CI matrices. It caps work in flight, not in progress — the concurrency group already means
only one of those three is ever being worked on.

## The rounds

Fix rounds are counted in **pushes by the agent**, not CI runs: the `instrumented` matrix is the
one job here that fails for reasons unrelated to the diff, and a flaky emulator must not spend the
budget of a branch that was fine.

- **1–8** — ordinary: read the failure, fix the cause.
- **9** — escalation. Handed the full attempt history (`git log -p`) instead of the latest
  failure, told that eight plausible-looking failures are evidence about the *diagnosis*, and
  permitted to `git revert` and take a different route, with a larger turn budget. These loops
  fail by an agent applying variations of a fix that never addressed the cause —
  indistinguishable from progress inside any single round.
- **10** — last ordinary round. On failure: draft the PR, label `agent:stuck`, comment with every
  round's subject and the final failure.

Drafting isn't cosmetic — it disarms auto-merge, exactly what's wanted the moment the loop admits
it's lost.

## When a stage stops before it finishes

A Claude run can end without finishing — a usage limit is the common cause, a cancelled runner or
GitHub incident the rest.

Nothing corrupt reaches the branch: the commit and push steps come *after* the Claude step in
every stage, so a dying run leaves the working tree in the runner and the branch exactly as it was.
The problem is the opposite: the pipeline advances on events, and a stage that never pushed emits
none, so no CI run follows, no review follows, and nothing calls the fix stage again. The round
counter doesn't move either — it counts pushes, so a run that pushed nothing spends nothing, and
the branch never reaches round 10 or gets drafted or labelled `agent:stuck`. It would simply go
quiet, auto-merge still armed, a red X in a tab nobody's watching.

Two mechanisms close this, since a workflow can't wake itself up.

**Detection, inside the stage.** Every Claude step is `continue-on-error`, so a failed run becomes
a value rather than a dead job. `.github/actions/agent-stall` labels the issue and pull request
`agent:stalled`, comments with a machine-readable marker naming the run, and exits non-zero so
everything downstream is skipped by the implicit `success()`.

The condition tests `steps.<id>.outcome`, not `.conclusion`: `continue-on-error` rewrites
`conclusion` to `success`, while `outcome` is what actually happened. It also checks the action's
*own* `conclusion` output, a different thing that happens to share the name.

`continue-on-error` is what makes the guard necessary: without the `exit 1`, the commit and push
steps would run after a half-finished Claude run and push whatever was left on disk.

**Retry, from outside.** `agent-retry.yml` runs on a schedule, finds `agent:stalled`, and re-runs
the recorded run — the only handle that works for all four stages, since the plan stage runs
against `main` with no branch to find it by, and the fix stage is a reusable workflow that can't
be dispatched at all.

It sweeps **every five hours**, matching the window a usage limit resets on; hourly would spend a
stage's worth of tokens four times over discovering the limit still holds. After three stalls —
about fifteen hours — it stops treating it as a usage window and hands the branch over as
`agent:stuck`, since retrying forever hides a real failure behind a label that looks handled.

The `session_id` output is unused so far: a retry could `--resume` the stalled session instead of
re-deriving the diagnosis, worth adding if stalls turn out common.

**The same workflow drains the queue.** A run displaced out of the concurrency group needs a
different handle: it was cancelled before its first step, so there's no stall marker to find and
nothing to re-run. `agent-retry.yml`'s second sweep recognises that shape instead — an open issue
still labelled `agent:planned`, none of the kill-switch labels, no open `agent/issue-N` pull
request — and dispatches **Agent · implement** for the oldest one. One issue per sweep, only when
no pipeline run is in flight, since starting two would undo the point of the group.

Re-dispatching is safe by construction: the implement stage resets its branch from `main` before
writing anything, and re-reads the plan from the issue body rather than the dispatch inputs, so the
sweep needn't carry a title or body and can't land the wrong plan or half of one. A redundant
dispatch just costs a run rebuilding the same branch.

It's bounded at three starts, the same budget the stall loop gets, counted the same way —
`<!-- agent-queued -->` in a `github-actions[bot]` comment, authorship-filtered since an issue
comment is world-writable. The bound matters because a run that reached "nothing was changed, no
pull request to open" leaves the same `agent:planned`-with-no-PR trace on every sweep. Unbounded,
that's a whole run spent every five hours forever — worse than the case `MAX_RETRIES` already
guards, since a stall costs a stopped run and this costs a complete one.

The sweep names the six workflows in the group explicitly, since the API doesn't report which
concurrency group a run holds. `Agent · fix` is absent from that list — a reusable workflow has no
runs of its own; its caller's name appears instead. Getting the list wrong is wasteful, not
dangerous: a missed name starts a run that queues behind the one already going.

## What the plan stage does with an awkward issue

There's no refusal verdict: every issue that reaches the plan stage is planned and handed to
implement, so an issue you relabel or reopen isn't silently dropped, unlike the earlier
`implementable: false` answer. A vague request is planned at its narrowest useful reading, with
that reading stated; a part that can't be done unattended goes into the plan's "out of scope"
section with the reason, rather than sinking the whole issue.

One limit is still routed around, since it's mechanical and would otherwise fail the branch at the
end instead of the start: `.github/workflows/` and `.github/actions/` can't be pushed by a token
without `workflow` scope.

Adding a dependency is planned for, not refused. Every build artifact is pinned by a SHA-256 in
`gradle/verification-metadata.xml`, so a new one is rejected before anything compiles — the
implement stage regenerates it, over the whole task set and with `--refresh-dependencies`, the
same way `dependabot-verification-metadata.yml` does. A runner is the right place for that: it sets
none of the dev shell's `aapt2FromMavenOverride`, so it resolves `com.android.tools.build:aapt2`
and records the two artifacts a local regeneration silently leaves out.

What used to be a decline is now a plan readable and editable before implement gets to it. Areas
`CLAUDE.md` flags for a human (a Room migration, a new locale, signing and release, the tracking
accuracy rules, `SessionRepository`'s invariants, device-identity keying) still need a careful
read of the plan, with the review stage and CI standing behind it.

## Prompt injection

Issue and comment bodies are attacker-controlled — this is a public tracker. Three things stand
between that and the repository, in descending order of how much they buy:

1. **The environment gate, plus the check that makes it mean something.** An outside issue doesn't
   reach a prompt until you approve it — but that gates the *run*, not the *text*: the plan is the
   issue body, an author can edit it any time, and approving then getting rewritten is a short path
   from a comment box to a push token. So the implement stage also checks who last edited the body,
   proceeding only if that was the plan stage or someone with write access.

   No hash is pinned at approval: the gate exists so you can read and change the plan, and a pin
   taken before your edit would refuse your own work. A body is a full replacement, so the last
   writer owns all of it — "who wrote it last" is the simpler question and the right one. Edit the
   plan as much as you like, before or after approving; what's refused is a body last touched by
   someone who couldn't have pushed the change themselves. The identity comes from GraphQL
   `userContentEdits`, sorted by timestamp rather than trusted to arrive in order, with `__typename`
   distinguishing the Actions app from a human account sharing its login — GraphQL omits the
   `[bot]` suffix REST uses.

   This is the real mitigation; the other two are defence in depth.
2. **Framing.** Every prompt carrying reported text delimits it and states plainly it's data
   describing a request, not instructions — the plan stage is told that text shaped like an
   instruction *to it* is itself grounds to decline.
3. **Tool policy.** The plan and review stages are read-only — `Write` and `Edit` are on their deny
   list — so they return a verdict and the workflow's own shell steps act on it. No stage but plan
   can dispatch another workflow.

### What the tool lists are and are not

The two writing stages get broad `Bash` with a deny list, rather than an enumerated allow list,
deliberate on both counts.

Enumerating was worse than it looked: every command an agent reaches for and lacks — `rg`, `jq`,
`find`, `wc` — costs turns out of a budget covering a feature, its tests and a bulk string edit. And
restricting `sed` while granting `Write` and `Edit` prevents nothing, since the capability is
already there by a shorter route.

So the deny list targets things not reachable another way: `gh` and other token-bearing commands,
the network (`curl`, `wget`, `nc`, `ssh`, `WebFetch`, `WebSearch`), and `git push`/`git remote`,
since the workflow owns the push and an agent pushing on its own would bypass the round counter.

**None of this is a sandbox.** The implement stage runs `./gradlew`, and the agent can edit the
build scripts Gradle executes — so anything the runner can do, a determined agent can, deny list or
not. That's not a flaw to close; it's what "an agent that builds and tests this app" means.
`actions/checkout` also leaves the push token in `.git/config` by default, which `Read` reaches
without a shell.

The controls that actually bound this sit elsewhere: the approval gate on outside issues, the
review stage, Android CI, and the PAT's own scope — limited to this repository, with no Workflows
permission, so an agent can't rewrite the gates that judge it.

Untrusted text always moves through the environment (`env:`), never interpolated into a `run:`
block: an issue body with shell metacharacters is ordinary, and interpolating one into a script is
how that becomes arbitrary code on a runner holding a push token.

**What none of this stops, stated plainly:** once you approve an outside issue, auto-merge is
armed exactly as for your own, and the resulting code can reach `main` without anyone reading the
diff. The approval gates *intent*, not output. Android CI is a strong gate but not an adversary
model — nothing in lint or coverage objects to a plausible change that quietly alters what
`reconcile` counts. If that trade stops looking right, the smallest fix is skipping the `Arm
auto-merge` step when `inputs.author != 'ElDavoo'`: same pipeline, outside issues land
green-and-approved awaiting your click.

## Testing

There's nothing to unit test — these are workflows, and the only way to run one is to run it. What
stands in for tests:

- `actionlint` runs in the `listing` job of `android-ci.yml` on every push. It type-checks every
  `${{ }}` against the event payload and runs shellcheck over each `run:` block — not optional: it
  caught ` #` starting a comment in a plain YAML scalar, which had silently truncated three `run:`
  values to an unterminated quote.
- The gate pattern — `needs: [gate]` with `always() && result != 'failure' && != 'cancelled'` — is
  the one piece of logic here subtle enough to fail silently. Without `always()` a *skipped* gate
  skips the guarded job too, the path your own issues take, so the pipeline would do nothing for
  you while working fine for everyone else. Worth proving once by temporarily inverting the `if:`
  so one of your own issues takes the gated path.

Run the linter locally the same way CI does:

```sh
nix shell nixpkgs#actionlint -c actionlint
```
