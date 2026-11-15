# Signing certificates

The public certificate for each of Carrier Pigeon's signing keys in Cloud KMS
(mobile-build-infra). Every build signed with a key carries that key's
certificate, so they are committed here and never regenerated: phones,
F-Droid and Play know the app by them.

| File | Key | Signs |
| --- | --- | --- |
| `dev.pem` | `carrier-pigeon-dev` | dev channel APKs (`io.github.jakescott.carrierpigeon.dev`), on Firebase App Distribution |
| `release.pem` | `carrier-pigeon-release` | prod APKs: GitHub releases and F-Droid |
| `play.pem` | `carrier-pigeon-play` | bundles uploaded to Play from main (upload key only) |

The first `release.yml` run for a key makes its certificate and attaches it
to the run. Commit it here, then run again. Never replace `release.pem` once
a release is out: an APK signed with another certificate can't update the
installed app.
