package security;

import util.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * TokenVault selects the best available TokenStore for the current platform.
 * Preference order: Platform-specific secure store -> encrypted file store.
 */
public class TokenVault {
    private static volatile TokenVault INSTANCE;

    private final TokenStore store;
    private final String storeType;

    private TokenVault(TokenStore store, String storeType) {
        this.store = store;
        this.storeType = storeType;
    }

    @SuppressWarnings("D")
    public static TokenVault init(Path baseDir) throws Exception {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir cannot be null");
        }
        
        // Validate and normalize path to prevent directory traversal
        Path normalizedBase = baseDir.toAbsolutePath().normalize();
        String basePath = normalizedBase.toString();
        if (basePath.contains("../") || basePath.contains("..\\")) {
            throw new IllegalArgumentException("Invalid base directory path");
        }
        
        if (INSTANCE == null) {
            synchronized (TokenVault.class) {
                if (INSTANCE == null) {
                    TokenStore chosen;
                    String type;

                    String os = System.getProperty("os.name", "").toLowerCase();

                    if (os.contains("windows")) {
                        try {
                            chosen = new WindowsDpapiTokenStore(normalizedBase);
                            type = "WINDOWS_DPAPI";
                            Logger.info("Using Windows DPAPI token store");
                        } catch (Throwable t) {
                            Logger.warn("Windows DPAPI unavailable. Falling back to encrypted file store.");
                            chosen = new FileTokenStore(normalizedBase);
                            type = "FILE";
                        }
                    } else if (os.contains("mac")) {
                        try {
                            chosen = new MacKeychainTokenStore();
                            type = "MACOS_KEYCHAIN";
                            Logger.info("Using macOS Keychain token store");
                        } catch (Throwable t) {
                            Logger.warn("macOS Keychain unavailable. Falling back to encrypted file store.");
                            chosen = new FileTokenStore(normalizedBase);
                            type = "FILE";
                        }
                    } else if (os.contains("linux")) {
                        try {
                            chosen = new LinuxSecretServiceTokenStore();
                            type = "LINUX_SECRET_SERVICE";
                            Logger.info("Using Linux Secret Service token store");
                        } catch (Throwable t) {
                            Logger.warn("Linux Secret Service unavailable. Falling back to encrypted file store.");
                            chosen = new FileTokenStore(normalizedBase);
                            type = "FILE";
                        }
                    } else {
                        chosen = new FileTokenStore(normalizedBase);
                        type = "FILE";
                        Logger.info("Using encrypted file token store");
                    }

                    INSTANCE = new TokenVault(chosen, type);
                }
            }
        }
        return INSTANCE;
    }

    public static TokenVault get() {
        if (INSTANCE == null) throw new IllegalStateException("TokenVault not initialized. Call init(baseDir) first.");
        return INSTANCE;
    }

    public String getStoreType() { return storeType; }

    // Legacy single token methods
    public void saveToken(String token) throws Exception { store.saveToken(token); }
    public Optional<String> loadToken() throws Exception { return store.loadToken(); }
    public void clear() throws Exception { store.clear(); }

    // Enhanced multi-key storage methods
    public void saveData(String key, String data) throws Exception {
        store.saveData(key, data);
    }

    public Optional<String> loadData(String key) throws Exception {
        return store.loadData(key);
    }

    public void saveMultipleData(Map<String, String> dataMap) throws Exception {
        store.saveMultipleData(dataMap);
    }

    public Map<String, String> loadAllData() throws Exception {
        return store.loadAllData();
    }

    public void clearData(String key) throws Exception {
        store.clearData(key);
    }
}
