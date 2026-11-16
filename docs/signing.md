# Signing and releases

Carrier Pigeon is signed with keys in Cloud KMS that never leave Google. The
keys, their signer accounts and the Workload Identity pool are in
[mobile-build-infra](https://github.com/jake-scott/mobile-build-infra)
(`build/apps.tf`, `docs/signing-model.md`). This repo has the workflow that
uses them, `.github/workflows/release.yml`.

## Channels

| Push to | Channel | Package, label | Signed with | Goes to |
| --- | --- | --- | --- | --- |
| any branch, PR | sideload | `….carrierpigeon.debug`, "Carrier Pigeon Debug" | Android debug key | `carrier-pigeon-debug` artifact (`build.yml`) |
| `dev` | dev | `….carrierpigeon.dev`, "Carrier Pigeon Dev" | `dev` | Firebase App Distribution, group `carrier-pigeon-dev` |
| `main` | internal | `dev.jakethesnake.carrierpigeon` | `play` (upload key, bundle) | Play internal track |
| tag `vX.Y.Z` | prod | same | `release` (APK) | GitHub release, and F-Droid builds the tag; the internal Play release built from the tagged commit is promoted to production |

The debug and dev builds each have their own package, so all three install
side by side and none can update another. Play bundles are signed with the
`play` upload key; Play App Signing re-signs them with a key Google holds, so
Play and F-Droid/GitHub builds carry different signatures and switching
between them means a reinstall. A tag doesn't build a new Play bundle: it
promotes the one main built from the same commit, which testers already had.

Each key's signer account trusts only `release.yml` on one branch, running
in the matching GitHub environment: the `dev` key the `dev` environment on
`refs/heads/dev`, the `release` and `play` keys `qa` (main pushes) and `prod`
(tags) on `refs/heads/main`. Signing from any other branch, file or
environment is refused by Google, whatever the workflow says. Uploads have
their own accounts: `app-distribute` (App Distribution only, in the
`mobile-build-dist` project) and the Play publisher.

A workflow triggered by a tag runs the tag's own copy, which the keys don't
trust and which could have been edited before tagging. So a tag push runs
`build.yml`, and when that succeeds `workflow_run` starts `release.yml` from
`main`. That copy checks the tag points at a commit already on `main` and
matches `versionName`, builds that commit, and signs with scripts and
certificates from `main`.

## How a release is signed

1. `setup` decides the channel from the push or tag. `build` (no
   credentials) builds the unsigned dev APK, Play bundle or prod APK; only
   dev uses a Gradle cache.
2. `sign-apk` (dev, prod) and `sign-bundle` (internal) run in the channel's
   environment, sign through the PKCS#11 library with the certificate in
   `signing-certs/`, and check the result carries that certificate
   (`scripts/kms_sign.sh`). They don't run Gradle.
3. dev: `distribute` uploads the APK to App Distribution and sends it to the
   tester group, with the commit subject as release notes.
4. internal: `upload-internal` uploads the bundle to Play's internal track.
   Its release name ends with the commit hash.
5. prod: `rebuild` builds the APK again on a fresh runner, and `verify`
   checks it is byte-for-byte the build we signed and that our signature
   copies onto it with `apksigcopier`: the check F-Droid makes. `publish`
   creates the GitHub release, then `promote` finds the internal Play
   release named for the tagged commit and puts it on production.

dev APKs take their version code from the run number (`-PdevVersionCode`),
Play bundles from the run number plus 100000 (`-PplayVersionCode`), and
release APKs from the source, so F-Droid can reproduce them.

APKs are signed with APK Signature Scheme v3 (v2 and v1 are left out, as minSdk 30 always checks v3), by a
small `apksig` program (`scripts/KmsApkSigner.java`), because `apksigner`
can't attach our certificate when the key comes through PKCS#11. Bundles are
signed with `jarsigner`, as in Tabby.

## Reproducible builds for F-Droid

F-Droid builds the `v<version>` tag, compares its APK with ours (`Binaries:`
in its metadata), and if they match apart from the signature, publishes our
signed APK. What keeps the build reproducible:

- `versionCode` and `versionName` come from `app/build.gradle.kts`, never
  from CI (only dev builds override the code).
- No dependency info block (`dependenciesInfo`) and no VCS info
  (`vcsInfo.include = false`) in the APK.
- Gradle is pinned by the wrapper's `distributionSha256Sum`, AGP and Kotlin
  by the version catalog, the JDK to 17.
- Signing adds only a signing block: no v1 signature, alignment kept.

Every prod run proves it with the rebuild check above. If it ever
fails, run `diffoscope` on the `unsigned` and `rebuild` artifacts.

For the F-Droid submission (fdroiddata), the metadata needs `Binaries:`
pointing at the GitHub release APK and `AllowedAPKSigningKeys:` set to the
SHA-256 of `signing-certs/release.pem`:

```sh
openssl x509 -in signing-certs/release.pem -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr A-F a-f
```

## Setting up (once)

1. **mobile-build-infra:** the `dev` and `release` keys must be
   `RSA_SIGN_PKCS1_4096_SHA512`; the `play` key stays SHA-256, because
   `jarsigner` is told the algorithm. The dev key and `app-distribute` trust
   `refs/heads/dev` (`dev_ref`, `distribute` in `build/apps.tf`).
2. **GitHub environments** in this repository: `dev` limited to the `dev`
   branch, `qa` and `prod` limited to `main` (tag releases run on `main`
   too). The pool only accepts tokens from jobs in an environment. Give
   `prod` required reviewers where the plan allows it. Take changes to `dev`
   through pull requests: anyone who can push there can sign with the dev
   key.
3. **Actions variables:** set `GCP_WIF_PROVIDER` on the repository, and
   `KMS_KEY` and `SIGNER` on each environment (the dev key in `dev`, the
   play key in `qa`, the release key in `prod`), from `tofu output` in
   mobile-build-infra. The README lists them, under "Release
   configuration". A channel whose variables aren't set builds but isn't
   signed.
4. **Firebase:** on the `dev` environment, set `FIREBASE_APP_ID` from
   `tofu output firebase_android_app_ids` in mobile-build-infra `build/`,
   `FIREBASE_DISTRIBUTOR` from `bootstrap/`, and `FIREBASE_TESTER_GROUP`,
   and create that tester group in the Firebase console (App Distribution,
   Testers & Groups). Until then `distribute` is skipped with a warning and
   the APK stays in the run's `signed-dev` artifact.
5. **Play:** create the app in Play Console and upload the first bundle by
   hand (the first main run makes `play.pem` and attaches the bundle). Then
   add the publisher account to mobile-build-infra (a Play Console user with
   release rights on this app only, bound to the `qa` and `prod`
   environments on `main`) and set the `PLAY_PACKAGE` (repository) and
   `PLAY_PUBLISHER` variables. Until then `upload-internal` and `promote` are skipped with a
   warning.
6. **First run per key.** A key with no certificate in `signing-certs/` gets
   one made, and the run attaches it instead of shipping. Commit
   `release.pem` and `play.pem` (`dev.pem` is already here), then run again.

## Making a release

1. Merge to `dev`; testers get the dev build through App Distribution.
2. Bump `versionCode` and `versionName` in `app/build.gradle.kts` and merge
   `dev` to `main`. The bundle goes to Play's internal track.
3. When it's good, tag a commit main built and push the tag:
   `git tag v1.2.0 origin/main && git push origin v1.2.0`. That makes the
   GitHub release, F-Droid picks the tag up, and the same internal build is
   promoted to production on Play.
