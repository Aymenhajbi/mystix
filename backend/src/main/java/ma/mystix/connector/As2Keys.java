package ma.mystix.connector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.stereotype.Component;

/**
 * The key material of an AS2 channel (ADR-0004), assembled in memory for each start or send and never written to
 * disk: Mystix's private key from the secret store ({@code credential_ref}), Mystix's certificate (role OWN) and the
 * partner's certificate (role PARTNER) from the channel. Aliases are the AS2 identifiers.
 */
@Component
class As2Keys {


    /** A PKCS#12 store and its one-off password, in memory. */
    record Material(KeyStore keyStore, char[] password, byte[] pkcs12, String localAlias, String partnerAlias,
                    X509Certificate own, X509Certificate partner) {
    }

    private final SecretStore secrets;
    private final CertificateService certificates;

    As2Keys(SecretStore secrets, CertificateService certificates) {
        this.secrets = secrets;
        this.certificates = certificates;
    }

    /** What is missing to sign, encrypt and verify on this channel; empty when everything is there. */
    List<String> missing(Channel channel) {
        java.util.ArrayList<String> problems = new java.util.ArrayList<>();
        if (channel.credentialRef() == null || secrets.read(channel.credentialRef()).isEmpty()) {
            problems.add("secret");
        }
        List<CertificateService.Certificate> certs = certificates.list(channel.companyId(), channel.id());
        if (certs.stream().noneMatch(c -> c.role() == CertificateService.Role.OWN)) {
            problems.add("certificate.own");
        }
        if (certs.stream().noneMatch(c -> c.role() == CertificateService.Role.PARTNER)) {
            problems.add("certificate.partner");
        }
        return problems;
    }

    Material load(Channel channel) {
        Map<String, Object> s = channel.settings();
        String local = String.valueOf(s.get("localAs2Id"));
        String partner = String.valueOf(s.get("partnerAs2Id"));
        List<CertificateService.Certificate> certs = certificates.list(channel.companyId(), channel.id());
        X509Certificate own = certs.stream().filter(c -> c.role() == CertificateService.Role.OWN)
                .reduce((a, b) -> a.notAfter().isAfter(b.notAfter()) ? a : b).map(c -> x509(c.pem()))
                .orElseThrow(() -> new IllegalStateException("no OWN certificate on channel " + channel.id()));
        X509Certificate other = certs.stream().filter(c -> c.role() == CertificateService.Role.PARTNER)
                .reduce((a, b) -> a.notAfter().isAfter(b.notAfter()) ? a : b).map(c -> x509(c.pem()))
                .orElseThrow(() -> new IllegalStateException("no PARTNER certificate on channel " + channel.id()));
        PrivateKey key = privateKey(secrets.read(channel.credentialRef())
                .orElseThrow(() -> new IllegalStateException("secret " + channel.credentialRef() + " missing")));
        char[] password = UUID.randomUUID().toString().toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry(local, key, password, new X509Certificate[] {own});
            store.setCertificateEntry(partner, other);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            store.store(out, password);
            return new Material(store, password, out.toByteArray(), local, partner, own, other);
        } catch (Exception e) {
            throw new IllegalStateException("AS2 key store of channel " + channel.id() + " could not be built", e);
        }
    }

    /** PKCS#8 or PKCS#1 PEM. Passphrase-protected keys are not supported yet. */
    static PrivateKey privateKey(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (object instanceof PEMKeyPair pair) {
                return converter.getPrivateKey(pair.getPrivateKeyInfo());
            }
            if (object instanceof PrivateKeyInfo info) {
                return converter.getPrivateKey(info);
            }
            throw new IllegalStateException("the secret is not an unencrypted private key");
        } catch (IOException e) {
            throw new IllegalStateException("the secret is not a readable private key", e);
        }
    }

    static X509Certificate x509(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException("stored certificate unreadable", e);
        }
    }

}
