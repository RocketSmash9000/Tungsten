package security;

import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.CleartextKeysetHandle;
import com.google.crypto.tink.KeyTemplates;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Stores the token encrypted-at-rest using Google Tink AEAD.
 * Keyset is generated on first use and stored under the config directory.
 */
public class FileTokenStore implements TokenStore {
    private static final byte[] ASSOCIATED_DATA = "tungsten-token-v1".getBytes(StandardCharsets.UTF_8);

    private final Path keysetFile;
    private final Path tokenFile;

    public FileTokenStore(Path baseDir) throws Exception {
        AeadConfig.register();
        this.keysetFile = baseDir.resolve("keys.json");
        this.tokenFile = baseDir.resolve("secret").resolve("token.enc");
        // Ensure dirs
        Files.createDirectories(this.tokenFile.getParent());
        // Ensure key exists
        ensureKeyset();
    }

    private void ensureKeyset() throws Exception {
        if (!Files.exists(keysetFile)) {
            KeysetHandle handle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"));
            Files.createDirectories(keysetFile.getParent());
            try (OutputStream os = Files.newOutputStream(keysetFile, StandardOpenOption.CREATE_NEW)) {
                CleartextKeysetHandle.write(handle, com.google.crypto.tink.JsonKeysetWriter.withOutputStream(os));
            }
        }
    }

    private Aead aead() throws IOException {
        try (InputStream is = Files.newInputStream(keysetFile)) {
            KeysetHandle handle = CleartextKeysetHandle.read(com.google.crypto.tink.JsonKeysetReader.withInputStream(is));
            return handle.getPrimitive(Aead.class);
        } catch (Exception e) {
            throw new IOException("Failed to load AEAD keyset", e);
        }
    }

    @Override
    public void saveToken(String token) throws Exception {
        if (token == null) throw new IllegalArgumentException("token is null");
        byte[] plaintext = token.getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = aead().encrypt(plaintext, ASSOCIATED_DATA);
        try (OutputStream os = Files.newOutputStream(tokenFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            os.write(ciphertext);
        }
    }

    @Override
    public Optional<String> loadToken() throws Exception {
        if (!Files.exists(tokenFile)) return Optional.empty();
        byte[] ciphertext = Files.readAllBytes(tokenFile);
        try {
            byte[] plaintext = aead().decrypt(ciphertext, ASSOCIATED_DATA);
            return Optional.of(new String(plaintext, StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Corrupted or wrong key
            return Optional.empty();
        }
    }

    @Override
    public void clear() throws Exception {
        if (Files.exists(tokenFile)) {
            try {
                // Overwrite with zeros for basic scrubbing
                byte[] zeros = new byte[(int) Files.size(tokenFile)];
                Files.write(tokenFile, zeros, StandardOpenOption.WRITE);
            } catch (Exception ignored) { }
            Files.deleteIfExists(tokenFile);
        }
    }
}
