# Signing and releases

Carrier Pigeon is signed with keys in Cloud KMS that never leave Google. The
keys, their signer accounts and the Workload Identity pool are in
[mobile-build-infra](https://github.com/jake-scott/mobile-build-infra)
(`build/apps.tf`, `docs/signing-model.md`). This repo has the workflow that
uses them, `.github/workflows/release.yml`.

## Channels

| Push to | Channel | Package, label | Signed with | Goes to |
| --- | --- | --- | --- | --- |
| `dev` | sideload | `….carrierpigeon.debug`, "Carrier Pigeon Debug" | Android debug key | `carrier-pigeon-debug` artifact (`build.yml`) |
| `main` | dev (insiders) | `….carrierpigeon.dev`, "Carrier Pigeon Dev" | `dev` | `signed-dev` artifact |
| tag `vX.Y.Z-rc.N` | qa | `io.github.jakescott.carrierpigeon` | `release` (APK), `play` (bundle) | GitHub prerelease |
| tag `vX.Y.Z` | prod | same | same | GitHub release; F-Droid builds the tag |

The debug and dev builds each have their own package, so all three install
side by side and none can update another. qa and
prod are one app with one key, so testers update from qa to prod in place.
Play bundles are signed with the `play` upload key; Play App Signing re-signs
them with a key Google holds, so Play and F-Droid/GitHub builds carry
different signatures and switching between them means a reinstall.

Each key's signer account only trusts `release.yml` as committed on
`refs/heads/main`, running in the matching GitHub environment (`dev`, `qa`
or `prod`). Signing from any other branch, file or environment is refused by
Google, whatever the workflow says.

A workflow triggered by a tag runs the tag's own copy, which the keys don't
trust and which could have been edited before tagging. So a tag push runs
`build.yml`, and when that succeeds `workflow_run` starts `release.yml` from
`main`. That copy checks the tag points at a commit already on `main` and
matches `versionName`, builds that commit, and signs with scripts and
certificates from `main`.

## How a release is signed

1. `setup` decides the channel from the push or tag. `build` (no credentials) builds the unsigned APK, and for qa and prod the
   bundle, with no Gradle cache.
2. `sign-apk` and `sign-bundle` run in the channel's environment, sign
   through the PKCS#11 library with the certificate in `signing-certs/`, and
   check the result carries that certificate (`scripts/kms_sign.sh`). They
   don't run Gradle.
3. For qa and prod, `rebuild` builds the APK again on a fresh runner, and
   `verify` checks it is byte-for-byte the build we signed and that our
   signature copies onto it with `apksigcopier`: the check F-Droid makes.
4. `publish` creates the GitHub release.

APKs are signed with APK Signature Scheme v2 and v3 only (minSdk 30), by a
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

Every qa and prod run proves it with the rebuild check above. If it ever
fails, run `diffoscope` on the `unsigned` and `rebuild` artifacts.

For the F-Droid submission (fdroiddata), the metadata needs `Binaries:`
pointing at the GitHub release APK and `AllowedAPKSigningKeys:` set to the
SHA-256 of `signing-certs/release.pem`:

```sh
openssl x509 -in signing-certs/release.pem -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr A-F a-f
```

## Setting up (once)

1. **mobile-build-infra:** the `dev` and `release` keys must be
   `RSA_SIGN_PKCS1_4096_SHA512`. They are `RSA_SIGN_PKCS1_4096_SHA256` today,
   and `apksig` (like `apksigner`) always signs with SHA-512 for a 4096-bit
   RSA key, which KMS refuses for a SHA-256 key. The `play` key stays
   SHA-256, because `jarsigner` is told the algorithm.
2. **GitHub environments** `dev`, `qa` and `prod` in this repository, each
   limited to the `main` branch (tag releases run on `main` too). The pool only accepts tokens from jobs in an
   environment. Give `prod` (and `qa`) required reviewers where the plan
   allows it.
3. **First run per key.** Push to `main`, then push an rc tag. A key with no
   certificate in `signing-certs/` gets one made, and the run attaches it
   instead of publishing. Commit `dev.pem`, `release.pem` and `play.pem` to
   `main`, then push a new rc tag. The first qa bundle is the one to upload to Play Console by hand
   when the app is created there.

## Making a release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` and merge
   to `main`.
2. Tag the merge commit for qa and push the tag:
   `git tag v1.2.0-rc.1 origin/main && git push origin v1.2.0-rc.1`.
   Testers install the prerelease APK.
3. When it's good, tag the same commit `v1.2.0` and push it. That makes the
   GitHub release, and F-Droid picks the tag up.
