# Security policy

## Reporting a vulnerability

Please report security problems privately, not in a public issue:
[open a private vulnerability report](https://github.com/jake-scott/carrier-pigeon/security/advisories/new)
(Security tab → Report a vulnerability).

Include what you found, how to reproduce it, and the app version or commit.
You should hear back within a week.

## Supported versions

Only the latest release is supported. Fixes ship in a new release, not as
patches to older ones.

## Verifying a release

Release APKs are signed with keys held in Cloud KMS; the public certificates
are in [`signing-certs/`](signing-certs/README.md), and
[`docs/signing.md`](docs/signing.md) describes how releases are built, signed
and checked for reproducibility.
