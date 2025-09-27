package security;

import java.util.Optional;
import java.nio.file.Path;
import util.Logger;

/**
 * TPM-backed token storage facade.
 * On Windows, delegates to WindowsDpapiTokenStore which leverages DPAPI.
 * Modern Windows commonly uses TPM to secure DPAPI master keys when available.
 * On other platforms, tries to use their respective libraries.
 * Upon failure, falls back into secure file storage.
 */
public class TpmTokenStore implements TokenStore {
    private final TokenStore delegate;
    private final String backend;

    public TpmTokenStore(Path baseDir) throws Exception {
        TokenStore chosen;
        String type;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("windows")) {
                // Use Windows DPAPI; if TPM is present, DPAPI benefits from it under the hood
                chosen = new WindowsDpapiTokenStore(baseDir);
                type = "WINDOWS_DPAPI";
            } else if (os.contains("mac")) {
                try {
                    chosen = new MacKeychainTokenStore();
                    type = "MACOS_KEYCHAIN";
                } catch (Throwable t) {
                    Logger.warn("macOS Keychain store init failed: " + t.getMessage() + ". Falling back to encrypted file store.");
                    chosen = new FileTokenStore(baseDir);
                    type = "FILE_FALLBACK";
                }
            } else if (os.contains("linux")) {
                try {
                    // Prefer Secret Service (libsecret) via secret-tool; robust and widely available on desktops
                    chosen = new LinuxSecretServiceTokenStore();
                    type = "LINUX_SECRET_SERVICE";
                } catch (Throwable t) {
                    Logger.warn("Linux Secret Service store init failed: " + t.getMessage() + ". Falling back to encrypted file store.");
                    chosen = new FileTokenStore(baseDir);
                    type = "FILE_FALLBACK";
                }
            } else {
                // TODO: Integrate with platform TPM (e.g., tpm2-tss) in the future.
                chosen = new FileTokenStore(baseDir);
                type = "FILE_FALLBACK";
            }
        } catch (Throwable t) {
            Logger.warn("TPM store init failed: " + t.getMessage() + ". Falling back to encrypted file store.");
            chosen = new FileTokenStore(baseDir);
            type = "FILE_FALLBACK";
        }
        this.delegate = chosen;
        this.backend = type;
    }

    @Override
    public void saveToken(String token) throws Exception { delegate.saveToken(token); }

    @Override
    public Optional<String> loadToken() throws Exception { return delegate.loadToken(); }

    @Override
    public void clear() throws Exception { delegate.clear(); }

    public String backend() { return backend; }
}
