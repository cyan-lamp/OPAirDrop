package com.oshare.airdrop.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;

/**
 * Keeps the AirDrop identity on disk so the {@code h=} we publish stays stable.
 *
 * <p>The certificate hash is what peers pin, and iOS caches it per device name: if
 * we regenerated on every launch, every receiver would see a "new" device and a
 * fresh prompt each time. So the key pair is generated once, then reloaded from
 * {@code identity.key} (PKCS#8) and {@code identity.der}.
 *
 * <p>Writes go through a temporary file and a rename, so a crash mid-write leaves
 * the previous identity intact instead of a half-written file. A pair that no
 * longer parses — truncated file, key/cert mismatch — is treated as absent and
 * replaced rather than surfacing as a startup failure.
 */
public final class IdentityStore {
    private static final String KEY_FILE = "identity.key";
    private static final String CERT_FILE = "identity.der";

    private final File directory;

    public IdentityStore(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory == null");
        this.directory = directory;
    }

    public File directory() {
        return directory;
    }

    public boolean exists() {
        return new File(directory, KEY_FILE).isFile() && new File(directory, CERT_FILE).isFile();
    }

    /**
     * Loads the stored identity, generating and persisting a new one when there is
     * none or the stored pair is unusable.
     *
     * <p>Generation costs a second or two on a phone, so callers must not run this
     * on the main thread.
     */
    public Identity loadOrCreate(String computerName) throws IOException, GeneralSecurityException {
        Identity stored = load(computerName);
        if (stored != null) return stored;
        Identity fresh = Identity.generate(computerName);
        save(fresh);
        return fresh;
    }

    /** Returns the stored identity, or {@code null} when there is nothing usable. */
    public Identity load(String computerName) {
        if (!exists()) return null;
        try {
            byte[] certificate = Files.readAllBytes(new File(directory, CERT_FILE).toPath());
            byte[] keyBytes = Files.readAllBytes(new File(directory, KEY_FILE).toPath());
            PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            Identity identity = Identity.load(key, certificate, computerName);
            // Identity.load parses the certificate but does not prove the pair matches;
            // a mismatched key would only fail later, during the TLS handshake.
            if (!identity.publicKey().equals(Identity.parse(certificate).getPublicKey())) return null;
            return identity;
        } catch (GeneralSecurityException | IOException | RuntimeException unusable) {
            return null;
        }
    }

    /** Persists the identity, replacing whatever was there. */
    public void save(Identity identity) throws IOException {
        ensureDirectory();
        write(new File(directory, KEY_FILE), identity.privateKey().getEncoded(), true);
        write(new File(directory, CERT_FILE), identity.certificate(), false);
    }

    /** Forgets the identity; the next {@link #loadOrCreate} makes a new device. */
    public void clear() {
        new File(directory, KEY_FILE).delete();
        new File(directory, CERT_FILE).delete();
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("cannot create " + directory);
        }
        // 0700: the private key is the one secret this module owns.
        directory.setReadable(false, false);
        directory.setWritable(false, false);
        directory.setExecutable(false, false);
        directory.setReadable(true, true);
        directory.setWritable(true, true);
        directory.setExecutable(true, true);
    }

    private void write(File target, byte[] content, boolean secret) throws IOException {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        Files.write(temp.toPath(), content);
        if (secret) {
            temp.setReadable(false, false);
            temp.setWritable(false, false);
            temp.setReadable(true, true);
            temp.setWritable(true, true);
        }
        try {
            Files.move(temp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException noAtomicRename) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
