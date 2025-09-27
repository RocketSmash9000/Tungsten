package security;

import com.sun.jna.platform.win32.Crypt32Util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Windows DPAPI-backed token storage for the current user.
 * The token is protected using the user's credential context and stored as an encrypted blob on disk.
 */
public class WindowsDpapiTokenStore implements TokenStore {
    // Use CRYPTPROTECT_UI_FORBIDDEN to prevent UI prompts
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    private final Path tokenFile;

    public WindowsDpapiTokenStore(Path baseDir) throws Exception {
        this.tokenFile = baseDir.resolve("secret").resolve("token.dpapi");
        Files.createDirectories(this.tokenFile.getParent());
    }

    @Override
    public void saveToken(String token) throws Exception {
        if (token == null) throw new IllegalArgumentException("token is null");
        byte[] plaintext = token.getBytes(StandardCharsets.UTF_8);
        // Protect for current user with UI forbidden
        byte[] protectedBlob = Crypt32Util.cryptProtectData(
            plaintext, 
            CRYPTPROTECT_UI_FORBIDDEN
        );
        Files.write(tokenFile, protectedBlob,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    @Override
    public Optional<String> loadToken() throws Exception {
        if (!Files.exists(tokenFile)) return Optional.empty();
        byte[] protectedBlob = Files.readAllBytes(tokenFile);
        try {
            byte[] plaintext = Crypt32Util.cryptUnprotectData(protectedBlob);
            return Optional.of(new String(plaintext, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    @Override
    public void clear() throws Exception {
        if (Files.exists(tokenFile)) {
            try {
                byte[] zeros = new byte[(int) Files.size(tokenFile)];
                Files.write(tokenFile, zeros, StandardOpenOption.WRITE);
            } catch (Exception ignored) {}
            Files.deleteIfExists(tokenFile);
        }
    }
}
