# CI and Android releases

## Everyday development

The `CI` workflow runs on pull requests into `main` and pushes to `main` when changes touch `app/**`, `gradle/**`, root Gradle build files/properties/wrappers or `.github/workflows/**`, `.github/actions/**`, `.github/scripts/**`. Changes only to README, `docs/**`, repository metadata or asset-generation tools skip CI entirely. A mixed change still runs CI if any file matches a build input.

Manual dispatch and the checks called by `Release` (`.github/workflows/android-checks.yml`, shared with CI) always run the full checks, independently of path filters. `Release` only starts on version-tag pushes or manual dispatch, so documentation-only branch pushes never start a release. Creating a version tag is an explicit release request and always runs checks and builds, even if the tagged commit only changed documentation. CI requires no signing secrets, including for fork pull requests.

To explicitly run all checks after a documentation-only change, use **Actions → CI → Run workflow** or:

```bash
gh workflow run ci.yml --ref main
```

CI uses Ubuntu 24.04, Temurin JDK 25, Android SDK 36.1, build-tools 36.0.0 and the checked-in Gradle wrapper. Gradle dependencies are cached; only `main` writes the shared cache. Older PR/branch CI runs are cancelled when a new run starts.

The checks are:

- Release notes generator tests using temporary git repositories (Python standard library only).
- `:app:testDebugUnitTest`: unit tests and Compose UI tests on Robolectric.
- `:app:lintDebug`: Android Lint; errors fail CI.
- `:app:assembleDebug`: an installable APK, available in the run's **Artifacts** for 14 days.
- `:app:assembleDebugAndroidTest`: compilation of the on-device smoke test. CI does not start an emulator or execute this test.

Test XML and HTML reports and lint reports are uploaded even when checks fail, with a 14-day retention period. A fresh runner generates its own debug signing key, so APKs from separate CI runs may require uninstalling an older debug install first. Locally, an existing root `debug.keystore` remains supported.

Screenshot tests run with the unit tests. CI records them (`-Proborazzi.test.record=true`) and saves them as the run's `screenshots-…` artifact. Pixel comparisons remain an explicit local check with `./gradlew :app:verifyRoborazziDebug`; update expected images intentionally with `./gradlew :app:recordRoborazziDebug` and review the image diff before committing.

## Screenshots on pull requests

For pull requests from branches of this repository, CI publishes the recorded screenshots to the orphan `ci-screenshots` branch under `pr-<number>/<run>-<attempt>/` and posts them as one comment on the pull request, marked with a hidden `<!-- ferra-screenshots -->` line and updated in place on every run. Screens whose images differ from the ones committed in `app/src/test/screenshots` are listed first; the rest are folded. Fork pull requests receive a read-only token, so their screenshots stay in the run's artifacts.

Only the posting job may write: it pushes to `ci-screenshots` and edits the comment (`contents: write`, `pull-requests: write`); the checks run with `contents: read`. When a pull request is closed, `Prune PR screenshots` removes its folder and rewrites `ci-screenshots` as a single commit, so old images stop taking space. Workflow actions are pinned to commit SHAs with the release in a comment.

Do not make this path-filtered workflow an unconditional required check in branch protection: documentation-only PRs intentionally have no CI run, so GitHub would leave that check pending.

## Signing setup

Under **Settings → Secrets and variables → Actions → Repository secrets**, configure:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 of the existing release `.jks` file. |
| `ANDROID_STORE_PASSWORD` | Keystore password. |
| `ANDROID_KEY_PASSWORD` | Signing key password. |
| `ANDROID_KEY_ALIAS` | Alias in the keystore; defaults to `upload` if unset or empty. |

Use the existing signing key so users can update their installed app. Keep a backup outside GitHub. Neither the keystore nor passwords belong in git. These secrets are only used in the release build job; ordinary CI receives none of them. Missing signing secrets fail the release before building, with an actionable error.

To upload the key without printing it or putting it on a command line:

```bash
base64 -w 0 /path/to/release.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_STORE_PASSWORD
gh secret set ANDROID_KEY_PASSWORD
gh secret set ANDROID_KEY_ALIAS
```

The workflow uses the built-in `GITHUB_TOKEN` to publish releases. No personal access token or Play Console credentials are needed. Only the publication job has `contents: write` permission. The decoded keystore is written to the runner's temporary directory and removed even on failure. Release builds disable Gradle's configuration cache and never write the shared Gradle cache.

## Publish a release

1. Set `versionName` in `app/build.gradle.kts` (for example `1.5.0`) and increase `versionCode` above the previous release. For a prerelease, use a matching name such as `1.5.0-rc.1`.
2. Merge the version change to `main` and let CI pass.
3. Tag that commit and push the tag:

   ```bash
   git switch main
   git pull --ff-only
   git tag -a v1.5.0 -m 'GitLab Mobile 1.5.0'
   git push origin v1.5.0
   ```

`Release` validates the tag against the app's version, reruns CI, builds `assembleRelease` and `bundleRelease`, verifies their signatures, and publishes:

- `gitlab-mobile-1.5.0.apk` for direct installation.
- `gitlab-mobile-1.5.0.aab` for a future Play Console upload.
- `SHA256SUMS` for the two files.

Release notes are generated directly from commit messages, including direct pushes to `main`; pull requests are not required. A tag with a hyphen creates a prerelease and is never marked latest. Signed files are also retained in the Actions run for 14 days. GitHub Release assets remain available independently of Actions retention. Nothing is automatically uploaded to Google Play.

To verify a download, place both binaries and `SHA256SUMS` in the same directory and run `sha256sum --check SHA256SUMS`.

## Release notes

Use Conventional Commit subjects describing the actual change:

| Commit | Release section |
| --- | --- |
| `feat: add global search` | Features |
| `fix(accounts): preserve the selected account` | Fixes (with the `accounts` scope) |
| `perf: reduce loading time` | Performance |
| `feat(auth)!: replace the token format` or a `BREAKING CHANGE:` footer | Breaking changes |
| `docs:`, `ci:` | Always omitted |
| `chore:`, `test:`, `build:`, `style:`, `refactor:` | Omitted unless marked as a breaking change |
| Other subjects without a known type | Other changes |

Messages appear in their original language; the generator groups them without rewriting or translating them. Every entry links to its commit. Merge messages are omitted, while the commits brought in by the merge are included. Releases containing only internal work say "No user-facing changes in this release." A full changelog link is always included.

The baseline is the highest lower version tag reachable from the target tag. Stable releases use the previous **stable** tag, so changes already included in an RC still appear in the final release. Prereleases may use a preceding prerelease (for example `rc.10` follows `rc.2`). Unmerged tags, higher versions and commits newer than the target tag are excluded. The first release includes all available history. The release checkout fetches full history and tags; the generator rejects shallow repositories.

Preview notes for an existing tag locally without publishing anything:

```bash
python3 .github/scripts/generate-release-notes.py \
  --tag v1.4.0 --repository FerraStack/gitlab-mobile --output /tmp/release-notes.md
cat /tmp/release-notes.md
```

Run the generator tests with `python3 -m unittest discover -s .github/scripts/tests -v`. The generated `release-notes.md` is saved alongside the signed binaries in the Actions artifact and passed to `gh release create --notes-file` at publication time.

## Failures and retries

If checks fail, download the reports from the Actions run, fix the commit and release a new version tag. A release is never published unless all checks and the signed build pass.

For a transient runner/network failure or after configuring missing secrets, use **Re-run failed jobs** in Actions. Alternatively dispatch the workflow on the existing tag:

```bash
gh workflow run release.yml --ref v1.5.0
```

Dispatching `Release` on a branch fails tag validation. Runs for the same tag are serialized. An already published release is not overwritten by retries: `gh release create` fails if it exists. If publication was interrupted, inspect the release and the saved signed artifact before retrying; do not replace binaries that users may already have downloaded.
