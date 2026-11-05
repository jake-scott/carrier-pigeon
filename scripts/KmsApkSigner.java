// Signs an APK with APK Signature Scheme v2 and v3, using a key in Cloud KMS
// reached through Google's PKCS#11 library, and attaching our long-lived
// certificate. Run by scripts/kms_sign.sh, as a single-file program:
//
//   java -cp apksig.jar KmsApkSigner.java <pkcs11.cfg> <alias> <cert.pem> <in.apk> <out.apk>
//
// Why not apksigner: through PKCS#11, apksigner takes the certificate from
// the token, and the KMS library only offers a throwaway one it generates
// on each run. Phones (and F-Droid's AllowedAPKSigningKeys) know an app by
// its certificate, so every build must carry the committed one.
//
// v1 (JAR) signing is off: minSdk is 30, and leaving META-INF alone means
// the signed APK is the unsigned one plus a signing block, which is exactly
// what F-Droid's apksigcopier moves onto its own rebuild. Alignment is kept
// as AGP wrote it for the same reason.
//
// apksig chooses the digest from the key size: SHA-256 up to 3072-bit RSA,
// SHA-512 above. The KMS key's algorithm has to match, or KMS refuses to
// sign (docs/signing.md).

import com.android.apksig.ApkSigner;
import com.android.apksig.KeyConfig;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;

public class KmsApkSigner {
    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("usage: KmsApkSigner <pkcs11.cfg> <alias> <cert.pem> <in.apk> <out.apk>");
            System.exit(2);
        }
        String config = args[0];
        String alias = args[1];
        Path certFile = Path.of(args[2]);
        File in = new File(args[3]);
        File out = new File(args[4]);

        Provider provider = Security.getProvider("SunPKCS11").configure(config);
        // apksig asks the JCA for a Signature by name; the provider has to be
        // registered for that to find the PKCS#11 key's implementation.
        Security.addProvider(provider);

        // Cloud KMS ignores the PIN, but Java wants one.
        char[] pin = System.getenv().getOrDefault("PKCS11_PIN", "unused").toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS11", provider);
        keyStore.load(null, pin);
        PrivateKey key = (PrivateKey) keyStore.getKey(alias, pin);
        if (key == null) {
            throw new IllegalStateException("No key " + alias + " on the token; aliases: "
                    + java.util.Collections.list(keyStore.aliases()));
        }

        X509Certificate cert;
        try (InputStream s = Files.newInputStream(certFile)) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(s);
        }
        // Signing with a certificate for some other key gives an APK that
        // fails to verify. Catch it here, with a clearer message.
        X509Certificate tokenCert = (X509Certificate) keyStore.getCertificate(alias);
        if (tokenCert == null || !tokenCert.getPublicKey().equals(cert.getPublicKey())) {
            throw new IllegalStateException(certFile + " is not the certificate for KMS key " + alias);
        }

        ApkSigner.SignerConfig signer =
                new ApkSigner.SignerConfig.Builder("CERT", new KeyConfig.Jca(key), List.of(cert)).build();
        new ApkSigner.Builder(List.of(signer))
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setAlignmentPreserved(true)
                .build()
                .sign();
        System.out.println("Signed " + out + " with " + alias);
    }
}
