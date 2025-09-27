package security;

import util.Logger;

import java.nio.file.Path;
import java.util.Optional;
import java.nio.file.Files;

// Windows registry access for TPM detection
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

/**
 * TokenVault selects the best available TokenStore for the current platform.
 * Preference order: TPM-backed -> encrypted file store.
 */
public class TokenVault {
    private static volatile TokenVault INSTANCE;

    private final TokenStore store;
    private final String storeType;

    private TokenVault(TokenStore store, String storeType) {
        this.store = store;
        this.storeType = storeType;
    }

    public static TokenVault init(Path baseDir) throws Exception {
        if (INSTANCE == null) {
            synchronized (TokenVault.class) {
                if (INSTANCE == null) {
                    TokenStore chosen;
                    String type;

                    if (isWindows()) {
                        try {
                            chosen = new WindowsDpapiTokenStore(baseDir);
                            type = "WINDOWS_DPAPI";
                            Logger.info("Using Windows DPAPI token store");
                        } catch (Throwable t) {
                            Logger.warn("Windows DPAPI unavailable: " + t.getMessage() + ". Falling back to platform store or file.");
                            try {
                                chosen = new TpmTokenStore(baseDir);
                                type = "PLATFORM";
                            } catch (Throwable t2) {
                                Logger.warn("Platform store unavailable: " + t2.getMessage() + ". Falling back to encrypted file store.");
                                chosen = new FileTokenStore(baseDir);
                                type = "FILE";
                            }
                        }
                    } else {
                        try {
                            // On macOS/Linux, prefer platform-secure stores encapsulated by TpmTokenStore
                            chosen = new TpmTokenStore(baseDir);
                            type = "PLATFORM";
                        } catch (Throwable t) {
                            Logger.warn("Platform store unavailable: " + t.getMessage() + ". Falling back to encrypted file store.");
                            chosen = new FileTokenStore(baseDir);
                            type = "FILE";
                        }
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

    public void saveToken(String token) throws Exception { store.saveToken(token); }

    public Optional<String> loadToken() throws Exception { return store.loadToken(); }

    public void clear() throws Exception { store.clear(); }

    private static TokenStore tryTpmOrFile(Path baseDir) throws Exception {
        if (isTpmAvailable()) {
            try {
                Logger.info("Attempting TPM-backed token store...");
                return new TpmTokenStore(baseDir);
            } catch (Throwable t) {
                Logger.warn("TPM store unavailable: " + t.getMessage() + ". Falling back to encrypted file store.");
            }
        }
        Logger.info("Using encrypted file token store at: " + baseDir.toString());
        return new FileTokenStore(baseDir);
    }

    private static boolean isTpmAvailable() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("windows")) {
                // Windows: Check registry for TPM presence
                final String key = "SOFTWARE\\Microsoft\\TPM\\State";
                boolean present = false;
                boolean ready = false;
                try {
                    int tpmPresent = Advapi32Util.registryGetIntValue(WinReg.HKEY_LOCAL_MACHINE, key, "TpmPresent");
                    present = (tpmPresent == 1);
                } catch (Throwable ignored) {}
                try {
                    int tpmReady = Advapi32Util.registryGetIntValue(WinReg.HKEY_LOCAL_MACHINE, key, "TpmReady");
                    ready = (tpmReady == 1);
                } catch (Throwable ignored) {}
                Logger.debug("TPM detection (Windows): present=" + present + ", ready=" + ready);
                return present; // consider available if present; readiness is optional for key storage
            }
            if (os.contains("linux")) {
                // Linux: Presence indicated by TPM char devices
                boolean hasTpmRm = Files.exists(Path.of("/dev/tpmrm0"));
                boolean hasTpm = Files.exists(Path.of("/dev/tpm0"));
                Logger.debug("TPM detection (Linux): /dev/tpmrm0=" + hasTpmRm + ", /dev/tpm0=" + hasTpm);
                return hasTpmRm || hasTpm;
            }
            // macOS typically lacks a generic TPM interface
            Logger.debug("TPM detection: unsupported OS for TPM detection");
            return false;
        } catch (Throwable t) {
            Logger.warn("TPM detection error: " + t.getMessage());
            return false;
        }
    }

    private static boolean isWindows() {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            return os.contains("windows");
        } catch (Throwable ignored) {
            return false;
        }
    }
}
