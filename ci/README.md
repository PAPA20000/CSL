# CI artifact quota fix

## Why this is a patch file and not a commit

The Arena GitHub App that authors commits in this repository does not hold the
`workflows` permission, so any push that touches `.github/workflows/` is
rejected outright:

```
! [remote rejected] refusing to allow a GitHub App to create or update
  workflow `.github/workflows/android.yml` without `workflows` permission
```

Worse, the rejection applies to the *whole push*, not just that file. Leaving the
change as a commit on this branch would block every subsequent push, so the fix
is parked here as a patch instead.

## The problem it fixes

CI run [#30369439631](https://github.com/rohit-45-95/CRAFT-STUDIO-LAUNCHER-/actions/runs/30369439631)
on PR #1 failed — but **not because the code failed to build**:

```
success  Build Release APK
success  Build Debug APK
success  Build APK without runtime
success  APK size report
failure  Upload artifacts     <-- only this
```

```
Error: Failed to CreateArtifact: Artifact storage quota has been hit.
```

Every run uploaded `out/*` — release, debug and no-runtime APKs, about 450 MB —
and kept it for 30 days. That accumulated to:

| | |
|---|---|
| Live artifacts | 28 |
| Total storage | ~12.7 GB |
| Earliest auto-expiry | 2026-08-15 |

The release and no-runtime APKs were also stored **twice**, because the
`Create GitHub Release` step already publishes them as release assets. Verified
on `v3.38`, `v3.37` and `v3.36` — each holds both APKs.

## What the patch changes

1. **No artifact upload on `pull_request`.** A PR only needs to prove the code
   compiles, which the build steps already do.
2. **Push and manual runs upload only the debug APK** plus checksums. The
   release and no-runtime APKs live in Releases already.
3. **Retention 30 → 7 days.**
4. **`compression-level: 0`** — APKs are already ZIP containers, so recompressing
   costs CI time for almost no saving.
5. **Adds `synchronize` to `pull_request` types.** Currently CI only fires on
   `opened` and `reopened`, so pushing new commits to an open PR never re-runs
   it — a PR can sit green while its latest commit was never built.

## Applying it

Either apply the patch:

```bash
git am ci/0001-ci-artifact-quota-fix.patch
git push origin <branch>
```

Or edit `.github/workflows/android.yml` by hand — the diff is small and readable.

Either way this must be done by an account with `workflows` permission, or by
granting that permission to the Arena GitHub App and asking the agent to retry.

## Immediate unblock (no workflow edit needed)

Deleting the accumulated artifacts frees the quota on its own and lets PR #1
pass on a re-run. The agent cannot do this — `DELETE /actions/artifacts/:id`
returns `403 Resource not accessible by integration`, as the App lacks
`actions: write`.

Via the UI: **Actions → any old run → Artifacts → delete**, or from a machine
with a token that has the `repo` scope:

```bash
REPO=rohit-45-95/CRAFT-STUDIO-LAUNCHER-
gh api "/repos/$REPO/actions/artifacts" --paginate \
  --jq '.artifacts[] | select(.expired==false) | .id' \
| while read id; do gh api -X DELETE "/repos/$REPO/actions/artifacts/$id"; done
```

Safe to run: all shipping APKs are preserved as release assets.

If nothing is done, the artifacts expire on their own between 2026-08-15 and
2026-08-17, after which CI recovers by itself.
