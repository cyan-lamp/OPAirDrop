package com.oshare.airdrop.core;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * The self-signed identity our AirDrop endpoint presents on port 8770.
 *
 * <p>Peers do not chain us to a CA; they compare the SHA-256 of the certificate we
 * serve against the {@code h=} value we publish in mDNS. So the certificate is
 * generated locally on first launch, its fingerprint is what the {@link
 * AirDropPeer} side must match, and the TLS stack is configured with a key manager
 * that presents exactly this certificate and a trust manager that either pins a
 * known fingerprint (sending) or accepts the peer's self-signed chain (receiving).
 *
 * <p>Everything here is plain JDK: DER assembly lives in {@link Der}, and no
 * KeyStore is involved, which keeps Android's KeyStore provider quirks out of the
 * picture.
 */
public final class Identity {
    /** Certificate hash algorithm Apple uses for the mDNS {@code h=} entry. */
    private static final String FINGERPRINT = "SHA-256";
    private static final String SIGNATURE = "SHA256withRSA";
    private static final String ALIAS = "airdrop";

    private static final String OID_SHA256_RSA = "1.2.840.113549.1.1.11";
    private static final String OID_RSA = "1.2.840.113549.1.1.1";
    private static final String OID_CN = "2.5.4.3";
    private static final String OID_BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String OID_KEY_USAGE = "2.5.29.15";
    private static final String OID_SUBJECT_KEY_ID = "2.5.29.14";
    private static final String OID_SUBJECT_ALT_NAME = "2.5.29.17";

    private final KeyPair keyPair;
    private final byte[] certificate;
    private final X509Certificate x509;
    private final String computerName;

    private Identity(KeyPair keyPair, byte[] certificate, X509Certificate x509, String computerName) {
        this.keyPair = keyPair;
        this.certificate = certificate;
        this.x509 = x509;
        this.computerName = computerName;
    }

    /** Generates a 2048-bit RSA identity valid from yesterday for ten years. */
    public static Identity generate(String computerName) throws GeneralSecurityException {
        long now = System.currentTimeMillis();
        return generate(computerName, now - 86_400_000L, tenYearsFrom(now));
    }

    /**
     * Generates an identity with an explicit validity window. Both bounds must
     * fall inside 1950..2049 because the certificate uses UTCTime.
     */
    public static Identity generate(String computerName, long notBefore, long notAfter)
        throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        byte[] certificate = buildCertificate(keyPair, computerName,
            new Date(notBefore), new Date(notAfter));
        X509Certificate parsed = parse(certificate);
        return new Identity(keyPair, certificate, parsed, computerName);
    }

    /** Rebuilds an identity from a stored key and certificate. */
    public static Identity load(PrivateKey key, byte[] certificate, String computerName)
        throws GeneralSecurityException {
        PublicKey publicKey = parse(certificate).getPublicKey();
        KeyPair keyPair = new KeyPair(publicKey, key);
        return new Identity(keyPair, certificate, parse(certificate), computerName);
    }

    public byte[] certificate() {
        return certificate.clone();
    }

    public X509Certificate x509() {
        return x509;
    }

    public PublicKey publicKey() {
        return keyPair.getPublic();
    }

    public PrivateKey privateKey() {
        return keyPair.getPrivate();
    }

    public String computerName() {
        return computerName;
    }

    /** Uppercase hex SHA-256 of the certificate, matching Apple's {@code h=} style. */
    public String certHash() {
        return fingerprint(certificate);
    }

    /** Hex digest used both for our own TXT record and for pinning a peer. */
    public static String fingerprint(byte[] der) {
        try {
            byte[] digest = MessageDigest.getInstance(FINGERPRINT).digest(der);
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                text.append(Character.forDigit((value >> 4) & 0x0f, 16));
                text.append(Character.forDigit(value & 0x0f, 16));
            }
            return text.toString().toUpperCase(Locale.ROOT);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Parses DER bytes back into a certificate; used by tests and by {@link #load}. */
    public static X509Certificate parse(byte[] der) throws GeneralSecurityException {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException broken) {
            throw new GeneralSecurityException("certificate does not parse", broken);
        }
    }

    // ------------------------------------------------------------------ TLS

    /** Server context: presents our certificate, accepts the peer's self-signed one. */
    public SSLContext serverContext() throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new KeyManager[] {new SingleKeyManager(keyPair, x509)},
            new TrustManager[] {new PinnedTrustManager(null)},
            new SecureRandom());
        return context;
    }

    /**
     * Client context that only accepts the peer whose certificate hashes to
     * {@code expectedFingerprint}. An empty or {@code null} fingerprint accepts
     * any chain, which is what discovery needs before TXT is trusted.
     *
     * <p>One context per fingerprint, kept. Building one is not free -- it loads the TLS
     * provider, seeds a {@link SecureRandom}, and measures in the hundreds of
     * milliseconds the first time a process does it -- and it was being built twice per
     * cold send, once for the warm-up and once more for the offer, because the two
     * callers both asked for a fresh one. Worse, a fresh context carries a fresh
     * {@code SSLSessionContext}, so the second connection could never resume the session
     * the first one had just negotiated: every reconnect paid a full handshake with a
     * certificate exchange on top. Sharing the context keeps the pinning identical
     * (the key here is the fingerprint the manager pins) and lets the platform's own
     * session cache do its job.
     */
    public static SSLContext clientContext(String expectedFingerprint)
        throws GeneralSecurityException {
        String key = expectedFingerprint == null ? "" : expectedFingerprint;
        SSLContext held = CLIENT_CONTEXTS.get(key);
        if (held != null) return held;
        long began = System.nanoTime();
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new PinnedTrustManager(expectedFingerprint)},
            new SecureRandom());
        SSLContext raced = CLIENT_CONTEXTS.putIfAbsent(key, context);
        SSLContext answer = raced == null ? context : raced;
        Trace.write("* client context for \"" + key + "\" built in "
            + (System.nanoTime() - began) / 1_000_000L + " ms");
        return answer;
    }

    /** Contexts already built, keyed by the fingerprint each one agrees to pin. */
    private static final Map<String, SSLContext> CLIENT_CONTEXTS = new ConcurrentHashMap<>();

    /** Presents a single certificate and private key; no KeyStore involved. */
    private static final class SingleKeyManager extends X509ExtendedKeyManager {
        private final PrivateKey key;
        private final X509Certificate certificate;
        private final X509Certificate[] chain;

        SingleKeyManager(KeyPair pair, X509Certificate certificate) {
            this.key = pair.getPrivate();
            this.certificate = certificate;
            this.chain = new X509Certificate[] {certificate};
        }

        private boolean usable(String keyType) {
            return keyType == null || keyType.equalsIgnoreCase("RSA");
        }

        @Override
        public String[] getClientAliases(String keyType, java.security.Principal[] issuers) {
            return usable(keyType) ? new String[] {ALIAS} : null;
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, java.security.Principal[] issuers,
                                        Socket socket) {
            for (String keyType : keyTypes) {
                if (usable(keyType)) return ALIAS;
            }
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, java.security.Principal[] issuers) {
            return usable(keyType) ? new String[] {ALIAS} : null;
        }

        @Override
        public String chooseServerAlias(String keyType, java.security.Principal[] issuers,
                                        Socket socket) {
            return usable(keyType) ? ALIAS : null;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyTypes,
                                              java.security.Principal[] issuers, SSLEngine engine) {
            for (String keyType : keyTypes) {
                if (usable(keyType)) return ALIAS;
            }
            return null;
        }

        @Override
        public String chooseEngineServerAlias(String keyType,
                                              java.security.Principal[] issuers, SSLEngine engine) {
            return usable(keyType) ? ALIAS : null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return ALIAS.equals(alias) ? chain.clone() : null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return ALIAS.equals(alias) ? key : null;
        }
    }

    /**
     * Accepts the self-signed certificates AirDrop peers use. When a fingerprint is
     * supplied, the leaf must hash to it, which pins the connection to the identity
     * advertised over mDNS.
     */
    private static final class PinnedTrustManager extends X509ExtendedTrustManager {
        private final String expected;

        PinnedTrustManager(String expectedFingerprint) {
            this.expected = expectedFingerprint == null ? "" : expectedFingerprint.toUpperCase(Locale.ROOT);
        }

        private void verify(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("empty chain");
            if (expected.isEmpty()) return;
            String actual = fingerprint(chain[0].getEncoded());
            if (!expected.equals(actual)) {
                throw new CertificateException("certificate fingerprint " + actual + " != " + expected);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
            verify(chain);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
            verify(chain);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    // ------------------------------------------------------- certificate build

    private static byte[] buildCertificate(KeyPair keyPair, String computerName,
                                           Date notBefore, Date notAfter)
        throws GeneralSecurityException {
        byte[] algorithm = Der.sequence(Der.oid(OID_SHA256_RSA), Der.nullValue());
        byte[] issuer = name(computerName);
        byte[] spki = subjectPublicKeyInfo(keyPair.getPublic());

        byte[] extensions = Der.explicit(3, Der.sequence(
            extension(OID_BASIC_CONSTRAINTS, true, Der.sequence()),
            extension(OID_KEY_USAGE, true, Der.bitString(new byte[] {(byte) 0xa0}, 5)),
            extension(OID_SUBJECT_KEY_ID, false,
                Der.octetString(sha1(keyInfo(keyPair.getPublic())))),
            extension(OID_SUBJECT_ALT_NAME, false,
                Der.sequence(Der.contextPrimitive(2, dnsName(computerName).getBytes(StandardCharsets.US_ASCII))))));

        byte[] tbs = Der.sequence(
            Der.explicit(0, Der.integer(2)),
            Der.integer(serial()),
            algorithm,
            issuer,
            Der.sequence(Der.utcTime(notBefore), Der.utcTime(notAfter)),
            name(computerName),
            spki,
            extensions);

        Signature signer = Signature.getInstance(SIGNATURE);
        signer.initSign(keyPair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();
        return Der.sequence(tbs, algorithm, Der.bitString(signature));
    }

    /** {@code SEQUENCE { SET { SEQUENCE { OID 2.5.4.3, UTF8String } } }}. */
    private static byte[] name(String commonName) {
        String value = commonName == null || commonName.isEmpty() ? "oShare" : commonName;
        return Der.sequence(Der.set(Der.sequence(Der.oid(OID_CN), Der.utf8(value))));
    }

    private static byte[] subjectPublicKeyInfo(PublicKey publicKey) throws GeneralSecurityException {
        return Der.sequence(
            Der.sequence(Der.oid(OID_RSA), Der.nullValue()),
            Der.bitString(keyInfo(publicKey)));
    }

    /** RSAPublicKey ::= SEQUENCE { modulus INTEGER, publicExponent INTEGER }. */
    private static byte[] keyInfo(PublicKey publicKey) throws GeneralSecurityException {
        if (!(publicKey instanceof java.security.interfaces.RSAPublicKey)) {
            throw new GeneralSecurityException("only RSA identities are supported");
        }
        java.security.interfaces.RSAPublicKey rsa = (java.security.interfaces.RSAPublicKey) publicKey;
        return Der.sequence(Der.integer(rsa.getModulus()), Der.integer(rsa.getPublicExponent()));
    }

    private static byte[] extension(String oid, boolean critical, byte[] value) {
        if (critical) {
            return Der.sequence(Der.oid(oid), Der.bool(true), Der.octetString(value));
        }
        return Der.sequence(Der.oid(oid), Der.octetString(value));
    }

    /** Positive, non-zero, and short enough to stay in eight bytes. */
    private static BigInteger serial() {
        byte[] random = new byte[8];
        new SecureRandom().nextBytes(random);
        random[0] = 0;
        return new BigInteger(1, random).add(BigInteger.ONE);
    }

    /**
     * A dNSName entry is IA5, so a non-ASCII device name cannot be used. The
     * fallback keeps the extension present and well-formed.
     */
    private static String dnsName(String computerName) {
        String base = computerName == null ? "" : computerName.trim();
        StringBuilder safe = new StringBuilder(base.length());
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            safe.append(c > 0x20 && c < 0x7f && c != '.' ? c : '-');
        }
        String label = safe.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        return (label.isEmpty() ? "oshare" : label) + ".local";
    }

    private static byte[] sha1(byte[] value) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-1").digest(value);
    }

    /** Ten years out, clamped so the certificate stays inside the UTCTime window. */
    private static long tenYearsFrom(long now) {
        long utcLimit = 2_524_607_999_000L; // 2049-12-31T23:59:59Z
        return Math.min(now + 10L * 365L * 86_400_000L, utcLimit);
    }
}
