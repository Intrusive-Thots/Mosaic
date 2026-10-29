# Improvement log and backlog

## 2026-10-29 — Mosaic branch reconciliation

### Objective and outcome

Merge every existing feature branch into `master`, resolve conflicts, and account for the open stacked pull requests. PR #15 had already merged the combined feature work. The merge introducing this record incorporates the two remaining branch heads, `ui-polish` and `fix/large-shape-freeze`, without changing application code, engine code, tests, dependencies, signing, or version 2.4.9. Existing branch names are retained.

The remaining open PRs (#7, #8, #9, #10, #11, #13, #14) contain work already reachable from `master`. They can be closed without merging their obsolete target branches again.

### Repository knowledge

| Item | Location or finding |
| --- | --- |
| Default branch before reconciliation | `master` at `b0de22ae88cba405590f055a31f2e4e3869a56fa` |
| Combined feature head, already included through #15 | `cursor/collage-tuning-loop-fcee` at `ab8b68cc9fa58567255a3d1c85a3cda67c39ce9f` |
| Android interface | `app/src/main/java/com/intrusivethots/mosaic/ui/` |
| Matching and rendering engine | Pure Kotlin `engine/` module |
| Build and CI | Gradle wrapper; `.github/workflows/ci.yml` |
| Branches already included | All eight `cursor/*` branches |
| UI branch | `ui-polish` at `84783bb244a3c859142a66132090957d37a81fd8` |
| Freeze branch | `fix/large-shape-freeze` at `40c2169def5f2428edfa3133686dde570f5c7099` |
| Repository instructions | No `AGENTS.md` found in the checkout or its workspace ancestors |

### Conflict decisions and evidence

- `PhoneStudio.kt`: retain the current Studio implementation. Each responsive layout already has one `GenerateBar`; settings use the shared design kit; `ResultPanel` returns before showing Save and Share when there is no output bitmap. Keep the inspector, Drive integration, and corrected sharing path.
- `README.md`: retain the current changelog through 2.4.9 rather than reverting to 2.4.3.
- `app/build.gradle.kts`: retain versionCode 15 and versionName 2.4.9.
- `ResidualPass.kt`: retain later stage clocks, cancellation, and placement changes. The old freeze fixes are already present.
- Stable patch ID `934a8678e6a1231323f2b411fc71b46996ad4cb4` proves `ba61372` and `0e6fda1` apply the same changelog patch.
- Stable patch ID `bbb2ef3a4020981c17c72f9568038e734f5c8d52` proves `40c2169` and `f498d24` apply the same freeze fix.
- After resolving both local merges, `git diff --exit-code origin/master HEAD` passed: the entire resolved tree was identical to the previously tested master tree before adding this record.
- Every fetched branch head passed `git merge-base --is-ancestor <branch> HEAD` against the resolved local merge.

### Validation

Baseline master CI passed: https://github.com/Intrusive-Thots/Mosaic/actions/runs/39907136472

That workflow covers engine tests, detekt, benchmarks, the bounded-memory collage test, Android lint and package builds, and emulator instrumented tests. A fresh run on the reconciliation commit is the final CI record. `git diff --check` and branch-ancestry checks passed locally.

Local `./gradlew --version` failed before any build or test ran because the environment could not reach `services.gradle.org` to obtain Gradle 9.6.1. Do not describe that attempt as a local test pass. Use GitHub Actions rather than changing the project's wrapper to work around this host restriction.

### Task retrospective

| Metric | Record |
| --- | --- |
| Files changed | This log only; runtime files remain identical |
| Tools | GitHub plugin, shell, git, Python |
| Commands used | `git clone`, `git status`, `git log`, `git show`, `git diff`, `git for-each-ref`, `git rev-list`, `git cherry`, `git merge-base`, `git patch-id --stable`, `git switch`, `git merge --no-commit --no-ff`, `git restore --source=origin/master`, `git commit`, `git rev-parse`, `rg --files`, `cat`, `sed`, `head`, `ls`, `du`, `ps`, `env`, `java -version`, `./gradlew --version`, `date`, Python |
| Runtime | Several minutes for local inspection and reconciliation; final build duration is recorded by Actions |
| Token estimate | Roughly 20,000–35,000 for preparation and integration; no exact token accounting is available |
| Accuracy | 5/5: ancestry, patch equivalence, and an unchanged production tree were checked |
| Efficiency | 3/5: initial metadata output was too broad |
| Automation | 4/5: reuse git history and existing CI; no manual product edits required |
| Complexity | 2/5: four conflict resolutions, all backed by existing implementations |
| Additional user prompts | 0 |
| New product code | 0 lines |
| New tools or skills | None: this is the first recorded instance; existing git and CI tools suffice |

Unnecessary searches: initial tool discovery included unrelated descriptions. Avoidable questions: none. Duplicate code generation: none. Existing utilities: git patch IDs and Actions were reused. Repeated logic: none. Repository knowledge is now indexed above.

Waste, ranked by estimated cost:

1. Printing complete PR metadata and bodies instead of selected fields: roughly 1–2 minutes of reading and unnecessary tokens. Discover exact tool names and parse `structuredContent.content` before displaying results.
2. Reading the full README when only build instructions and the recent changelog were needed: under a minute. Read named sections and changed hunks.
3. A guessed source path for `StudioScreen.kt`: a few seconds. Use the file list before reading a path.
4. Initial broad filesystem inventory: a few seconds. Limit instruction checks to the checkout and known ancestors.

No repeated debugging or new implementation was needed. One failed local wrapper download established that CI was the appropriate validation environment; no network retries or SDK bootstrap were attempted.

### Reusable merge workflow

1. Fetch all branch heads once and list open PRs with number, head SHA, base ref, draft state, and merge status.
2. Check default-branch history before attempting merges; a combined PR may already have landed while its stacked PRs remain open.
3. For each remaining branch, inspect `git rev-list --left-right --count master...branch` and `git cherry master branch`.
4. Use `git merge-base --is-ancestor` for fully incorporated branches. For duplicate patches, confirm stable patch IDs rather than reverting later code to make textual conflicts disappear.
5. Resolve genuinely divergent files by checking the intended behavior against the current implementation. Preserve later fixes and version metadata.
6. Verify the resolved tree, record the original heads as merge parents, and update the target with an expected-head check. Never force-rewrite shared history for this cleanup.
7. Confirm every original branch head is reachable from the new master, close superseded PRs, and inspect the resulting Actions run.

Current workflow: broad metadata reads → discover prior merge → inspect divergences → prove equivalence → reconcile history → CI.

Preferred workflow: compact PR/branch snapshot → check default history first → inspect only unique changes → reconcile history → CI.

Expected savings next time: approximately 2–4 minutes, several large metadata responses, and 2–4 shell/API calls. No additional user prompts or manual application edits should be needed. This workflow can become a helper only if it recurs; do not add speculative tooling now.

### Improvement queue

| Priority | Description | Estimated time saved | Difficulty | Dependencies | Frequency | Status |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | Use the compact ancestry-first merge workflow above | 2–4 minutes per reconciliation | Low | git and repository access | Every branch reconciliation | Ready for reuse |
| P2 | Consider running `:app:testDebugUnitTest` in CI; the current Android job builds and lints but does not run that task | Avoid missed app-unit regressions | Low | A scoped CI change and a successful run | Every code PR | Documented, outside this merge |
| P3 | Consider deleting obsolete remote branches after confirming nobody still uses their names | Less branch-list clutter | Low | Explicit cleanup decision | After integration batches | Deferred; branches retained |

Recorded-task count: 1. There is no historical baseline for runtime, token, or reuse trends yet. Revisit this queue after three comparable tasks, consolidate repeated workflows, and promote a helper only when it removes observed repeated work.
