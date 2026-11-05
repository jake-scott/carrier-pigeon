#!/usr/bin/env bash
# Signs Carrier Pigeon builds with keys in Cloud KMS, so no private key ever
# leaves Google. Java and OpenSSL reach the key through Google's PKCS#11
# library for Cloud KMS (libkmsp11). Adapted from Tabby's
# mobile/scripts/kms_sign.sh.
#
#   kms_sign.sh cert <out.pem> <common name>   make the key's certificate, once
#   kms_sign.sh apk <in.apk> <out.apk> <cert>  sign an APK (v2/v3, apksig)
#   kms_sign.sh aab <bundle.aab> <cert>        sign a bundle in place (jarsigner)
#
# Environment:
#   KMS_KEY          the key's resource name,
#                    projects/<p>/locations/<l>/keyRings/<r>/cryptoKeys/<k>
#                    (mobile-build-infra: tofu output signing_keys)
#   KMS_DIGEST       sha256 or sha512: the digest in the key's KMS algorithm
#                    (RSA_SIGN_PKCS1_4096_SHA256 -> sha256). Default sha256.
#   KMSP11_LIBRARY   path to libkmsp11.so
#   APKSIG_JAR       path to apksig-<version>.jar (apk only)
# Credentials are Application Default Credentials. The account needs
# roles/cloudkms.viewer on the ring and roles/cloudkms.signerVerifier on the
# key, which mobile-build-infra gives each key's signer account.
#
# Phones, F-Droid and Play know a signer by its certificate, so the same one
# must be on every build. The library only makes throwaway certificates, so
# `cert` makes a long-lived self-signed one with the KMS key, once per key,
# and the other modes always attach that one. Certificates are public and
# committed in signing-certs/<key>.pem.
#
# `cert` also needs OpenSSL's pkcs11 engine (libengine-pkcs11-openssl).
set -euo pipefail

usage() {
  sed -n '7,9p' "$0" | sed 's/^# //' >&2
  exit 2
}

mode=${1:-}
: "${KMS_KEY:?set KMS_KEY}"
: "${KMSP11_LIBRARY:?set KMSP11_LIBRARY}"
digest=${KMS_DIGEST:-sha256}
case $digest in
  sha256) sigalg=SHA256withRSA ;;
  sha512) sigalg=SHA512withRSA ;;
  *) echo "KMS_DIGEST must be sha256 or sha512" >&2; exit 2 ;;
esac

if [[ ! $KMS_KEY =~ ^(projects/[^/]+/locations/[^/]+/keyRings/[^/]+)/cryptoKeys/([^/]+)$ ]]; then
  echo "KMS_KEY must be projects/<p>/locations/<l>/keyRings/<r>/cryptoKeys/<k>" >&2
  exit 2
fi
key_ring=${BASH_REMATCH[1]}
# The library labels each key with its CryptoKey id; that is the alias Java
# sees and the object OpenSSL looks up.
key_label=${BASH_REMATCH[2]}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The library refuses a configuration file anyone but its owner can write.
umask 077
cat >"$work/kmsp11.yaml" <<EOF
---
tokens:
  - key_ring: "$key_ring"
# Java's PKCS#11 provider only sees private keys that have a certificate.
generate_certs: true
# The keys are software-protected; without this the library only exposes
# HSM keys.
allow_software_keys: true
EOF
export KMS_PKCS11_CONFIG="$work/kmsp11.yaml"

cat >"$work/pkcs11.cfg" <<EOF
name = kms
library = $KMSP11_LIBRARY
slotListIndex = 0
EOF

# Cloud KMS ignores the PIN, but Java and OpenSSL want one.
pin=${PKCS11_PIN:-unused}
export PKCS11_PIN=$pin

fingerprint() {
  openssl x509 -in "$1" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | tr '[:upper:]' '[:lower:]'
}

case $mode in
  cert)
    [ $# -eq 3 ] || usage
    out=$2
    cn=$3
    cat >"$work/openssl.cnf" <<EOF
openssl_conf = openssl_init
[openssl_init]
engines = engine_section
[engine_section]
pkcs11 = pkcs11_section
[pkcs11_section]
engine_id = pkcs11
MODULE_PATH = $KMSP11_LIBRARY
init = 0
[req]
distinguished_name = dn
[dn]
EOF
    # 30 years. An APK signing certificate's expiry isn't enforced on
    # install, but Play wants upload keys valid well past any app's life.
    OPENSSL_CONF="$work/openssl.cnf" openssl req -new -x509 "-$digest" -days 10950 \
      -engine pkcs11 -keyform engine \
      -key "pkcs11:object=$key_label;type=private;pin-value=$pin" \
      -subj "/CN=$cn/O=Carrier Pigeon" -out "$out"
    echo "Wrote the certificate for $KMS_KEY to $out:"
    openssl x509 -in "$out" -noout -subject -enddate -fingerprint -sha256
    ;;

  apk)
    [ $# -eq 4 ] || usage
    in=$2
    out=$3
    cert=$4
    : "${APKSIG_JAR:?set APKSIG_JAR}"
    java -cp "$APKSIG_JAR" "$(dirname "$0")/KmsApkSigner.java" \
      "$work/pkcs11.cfg" "$key_label" "$cert" "$in" "$out"

    # Check the APK verifies and carries the expected certificate before
    # anything publishes it.
    apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -1)
    certs=$("$apksigner" verify --verbose --print-certs "$out")
    echo "$certs"
    want=$(fingerprint "$cert")
    # "Signer #1 certificate ..." or "V3.0 Signer: certificate ...", by
    # apksigner version and by which schemes apksig wrote.
    got=$(grep -m1 -oE 'certificate SHA-256 digest: [0-9a-f]{64}' <<<"$certs" | awk '{print $NF}')
    if [ "$want" != "$got" ]; then
      echo "$out is signed with certificate $got, expected $want" >&2
      exit 1
    fi
    echo "Signed $out with $KMS_KEY (certificate SHA-256 $want)"
    ;;

  aab)
    [ $# -eq 3 ] || usage
    bundle=$2
    cert=$3
    jarsigner -keystore NONE -storetype PKCS11 \
      -providerClass sun.security.pkcs11.SunPKCS11 -providerArg "$work/pkcs11.cfg" \
      -storepass "$pin" -certchain "$cert" \
      -sigalg "$sigalg" -digestalg SHA-256 -sigfile UPLOAD \
      "$bundle" "$key_label"

    # jarsigner happily attaches a certificate for a different key; such a
    # bundle verifies as unsigned. Check the signature is valid and carries
    # the expected certificate.
    verify=$(jarsigner -verify "$bundle" 2>&1)
    if ! grep -q '^jar verified\.' <<<"$verify" || grep -q 'treated as unsigned' <<<"$verify"; then
      echo "$verify" >&2
      echo "$bundle did not verify: is $cert the certificate for $KMS_KEY?" >&2
      exit 1
    fi
    want=$(fingerprint "$cert")
    got=$(keytool -printcert -jarfile "$bundle" | sed -n 's/^[[:space:]]*SHA256: //p' | head -1 | tr -d : | tr '[:upper:]' '[:lower:]')
    if [ "$want" != "$got" ]; then
      echo "$bundle is signed with certificate $got, expected $want" >&2
      exit 1
    fi
    echo "Signed $bundle with $KMS_KEY (certificate SHA-256 $want)"
    ;;

  *)
    usage
    ;;
esac
