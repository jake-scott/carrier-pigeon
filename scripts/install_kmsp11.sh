#!/usr/bin/env bash
# Installs Google's PKCS#11 library for Cloud KMS on a GitHub runner and sets
# KMSP11_LIBRARY. The tarball is pinned by hash (KMSP11_VERSION,
# KMSP11_SHA256 in release.yml) and the library's signature is checked
# against Google's release key as well. From Tabby's android.yml.
set -euo pipefail
: "${KMSP11_VERSION:?}" "${KMSP11_SHA256:?}" "${RUNNER_TEMP:?}" "${GITHUB_ENV:?}"

cd "$RUNNER_TEMP"
curl --silent --show-error --fail --location -o kmsp11.tar.gz \
  "https://github.com/GoogleCloudPlatform/kms-integrations/releases/download/pkcs11-v$KMSP11_VERSION/libkmsp11-$KMSP11_VERSION-linux-amd64.tar.gz"
echo "$KMSP11_SHA256  kmsp11.tar.gz" | sha256sum --check --strict
tar xzf kmsp11.tar.gz
lib="$RUNNER_TEMP/libkmsp11-$KMSP11_VERSION-linux-amd64/libkmsp11.so"
# Google's release signing key, from the library's user guide.
cat > kmsp11-release.pem <<'PEM'
-----BEGIN PUBLIC KEY-----
MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEtfLbXkHUVc9oUPTNyaEK3hIwmuGRoTtd
6zDhwqjJuYaMwNd1aaFQLMawTwZgR0Xn27ymVWtqJHBe0FU9BPIQ+SFmKw+9jSwu
/FuqbJnLmTnWMJ1jRCtyHNZawvv2wbiB
-----END PUBLIC KEY-----
PEM
openssl dgst -sha384 -verify kmsp11-release.pem -signature "$lib.sig" "$lib"
echo "KMSP11_LIBRARY=$lib" >> "$GITHUB_ENV"
