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
| `main` | internal | `….carrierpigeon.internal`, "Carrier Pigeon Internal" | `play` (upload key, bundle) | internal track of a separate Play app |
| tag `vX.Y.Z-rc.N` | closed | `dev.jakethesnake.carrierpigeon` | `play` (upload key, bundle) | a new bundle of the tagged commit, versionName `X.Y.Z` from the tag, uploaded to the closed track (`alpha`) |
| tag `vX.Y.Z` | prod | same | `release` (APK) | after approval: the rc's closed Play build is promoted to production, then the GitHub release, and F-Droid builds the tag |

The debug, dev and internal builds each have their own package and launcher
icon, so all four install side by side and none can update another. That
makes internal testing a separate app in Play Console (Android can't
install two tracks of one package together); its builds stay on its
internal track. Play bundles are signed with the
`play` upload key; Play App Signing re-signs them with a key Google holds, so
Play and F-Droid/GitHub builds carry different signatures and switching
between them means a reinstall. An rc tag builds its own bundle of the
tagged commit, with versionName taken from the tag, so closed testers see the
release's version; it's a new build, not the bytes internal testers ran. The
release tag doesn't build for Play: it moves the rc's bundle from closed to
production, unchanged, so production users get the bytes closed testers ran.

Each key's signer account trusts only `release.yml` on one branch, running
in the matching GitHub environment: the `dev` key the `dev` environment on
`refs/heads/dev`, the `play` key `qa` (main pushes and rc tags) and the `release` key
`prod` (release tags), both on `refs/heads/main`. Signing from any other
branch, file or environment is refused by Google, whatever the workflow
says. Uploads have their own accounts: `app-distribute` (App Distribution
only, in the `mobile-build-dist` project), the Play tester (testing tracks
of the app and its `.internal` app only, trusted by `qa`) and the Play publisher (production of
this app, trusted by `prod` only). `prod` has a required reviewer, so
nothing reaches production users, and nothing is signed with the key
F-Droid users trust, without that approval.

What `qa` can still do: anything running there (a main push, or a
compromised action in one of its jobs) can put a build on the internal and
closed tracks, which reach real testers. Main's branch protection is their
gate. Open testing is left unconfigured in Play Console, because the tester
permission would reach it too.

A workflow triggered by a tag runs the tag's own copy, which the keys don't
trust and which could have been edited before tagging. So a tag push runs
`build.yml`, and when that succeeds `workflow_run` starts `release.yml` from
`main`. That copy checks the tag points at a commit already on `main` (and,
for `vX.Y.Z`, matches `versionName`), and signs and publishes with scripts and
certificates from `main`. A ruleset keeps `v*` tags from being moved or
deleted; to run a tag again, use "Run workflow" on `main` with the tag's
name.

### Which bundle the release tag promotes

Play release names are never trusted: anything that can write to a testing
track can name a release whatever it likes. Instead:

1. Each rc run attests its signed bundle (GitHub build provenance: made by
   `release.yml` on `main`), named
   `carrier-pigeon-<version>-<version code>-<commit>.aab`, and keeps it as
   the `bundle-closed-<rc tag>` artifact for 90 days. A tag's run is a
   `workflow_run` on `main`, so the commit the attestation records is main's
   head at the time; the attested file name is what ties it to the tagged
   commit.
2. For a release tag, `bundle` (no credentials) takes the latest rc tag on
   the same commit, finds its artifact, verifies the attestation and that
   the attested name is this version and commit, and writes tag, rc,
   version code and bundle SHA-256 to the run summary.
3. `scripts/play_promote.sh` promotes only if the closed track holds exactly
   that version code as its completed release, and Play's bundle for that
   code has that SHA-256. Play never reuses a version code. The check and
   the promotion are one Play edit, so a track that changes meanwhile makes
   it fail.

So give the release tag the same commit as the latest rc closed testers
have, within 90 days of that rc.

## How a release is signed

1. `setup` decides the channel from the push or tag. `build` (no
   credentials) builds the unsigned dev APK, Play bundle or prod APK; only
   dev uses a Gradle cache. An rc tag's bundle gets versionName from the
   tag (`-PplayVersionName`).
2. dev: `sign-apk` signs in `dev` through the PKCS#11 library with the
   certificate in `signing-certs/`, and checks the result carries that
   certificate (`scripts/kms_sign.sh`). `distribute` uploads the APK to App
   Distribution and sends it to the tester group, with the commit subject as
   release notes.
3. internal (main push) and closed (rc tag): `sign-bundle` signs the bundle
   with the upload key in `qa` and attests it; `upload-play` uploads it, as
   the Play tester, to the internal track of `<PLAY_PACKAGE>.internal` for
   main and to `alpha` of `PLAY_PACKAGE` for an rc.
   Its release name shows the commit (and the tag), for people.
4. prod (release tag): `bundle` as above; `rebuild` builds the APK again on
   a fresh runner and `verify-build` checks it is byte-for-byte the first
   build. Then `ship`, the one job behind the `prod` approval, signs the APK
   with the release key and, as the Play publisher, promotes the closed
   build to production. It runs no Gradle or pip. `verify-signature` (no
   credentials) checks our signature copies onto the rebuild with
   `apksigcopier`, pinned by hash: the check F-Droid makes.
   `github-release` creates the GitHub release with the APK `ship` signed.
   A re-run skips what's already done. Approve within 3 days: the unsigned
   APK `ship` signs is kept that long. After that, run the tag again from
   "Run workflow".

dev APKs take their version code from the run number (`-PdevVersionCode`),
Play bundles from the run number plus 100000 (`-PplayVersionCode`), and
release APKs from the source, so F-Droid can
reproduce them.

Version names: release APKs and main's bundles use `versionName` from
`app/build.gradle.kts`; an rc's bundle uses the tag's `X.Y.Z`. dev and debug
builds add the commit count and short SHA, for example
`0.1.0-dev.15+11a78e2` (dev), `0.1.0-dev-debug.15+11a78e2` and
`0.1.0-debug.15+11a78e2` (the sideload debug build).

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
   too). The pool only accepts tokens from jobs in an environment. `prod`:
   required reviewer (you), "Prevent self-review" off, "Allow
   administrators to bypass" off. Take changes to `dev` and `main` through
   pull requests: anyone who can push to `dev` can sign with the dev key,
   and anyone who can push to `main` reaches internal and closed testers.
   Add a tag ruleset on `v*`: only you can create tags, and nobody can
   update or delete them.
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
   hand (the first main run makes `play.pem` and attaches the bundle). In
   Users and permissions, invite both accounts from mobile-build-infra
   (`tofu output play_testers` and `play_publishers`) on this app only: the
   tester with "Release apps to testing tracks" and nothing else, the
   publisher with "Release to production, exclude devices, and use Play
   App Signing". Set up the closed track (`alpha`) with a Google Group as
   its testers, and leave open testing off. Set `PLAY_PACKAGE`
   (repository) and `PLAY_PUBLISHER` (the tester on `qa`, the publisher on
   `prod`). Until then the Play jobs are skipped with a warning.

   Internal testing is a second app, `<PLAY_PACKAGE>.internal` ("Carrier
   Pigeon Internal"). Create it in Play Console too, take the
   `bundle-internal` artifact from a main run (its `upload-play` job fails
   until the app exists) and upload it by hand to the internal track, with
   Play App Signing on. Give the tester account "Release apps to testing
   tracks" on it as well (not the publisher: it never goes to production),
   and add the internal testers. It uses the same upload key, so nothing
   changes in mobile-build-infra or the Actions variables. Then re-run the
   failed job.
6. **First run per key.** A key with no certificate in `signing-certs/` gets
   one made, and the run attaches it instead of shipping. Commit
   `release.pem` and `play.pem` (`dev.pem` is already here), then run again.

## Making a release

1. Merge to `dev`; testers get the dev build through App Distribution.
2. Bump `versionCode` and `versionName` in `app/build.gradle.kts` (F-Droid
   builds the release tag from them, and the release tag must match) and
   merge `dev` to `main`. The bundle goes to the internal track of
   "Carrier Pigeon Internal", which installs beside the closed or
   production app.
3. When internal looks good, tag main's latest build as a release
   candidate: `git tag -a v1.2.0-rc.1 -m v1.2.0-rc.1 origin/main && git push
   origin v1.2.0-rc.1`. That commit is built again as 1.2.0 and goes to
   closed testers on `alpha`. Fixes
   go through `dev` and `main` again, then `-rc.2` and so on.
4. When closed testing is done, tag the same commit as the rc:
   `git tag -a v1.2.0 -m v1.2.0 v1.2.0-rc.2 && git push origin v1.2.0`.
   Read the run summary, then approve the `prod` deployment. The closed
   build goes to production, then the GitHub release is made and F-Droid
   picks the tag up.

The first production release of a new personal developer account needs a
closed test with at least 12 opted-in testers for 14 days in a row, then an
application for production access in Play Console. Until that's granted,
`ship` fails at the Play step and no GitHub release is made; re-run it once
access is granted.

F-Droid: when the app is submitted to fdroiddata, its update check must
ignore rc tags, for example `UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$`.
