package security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stores the token in macOS Keychain using the `security` CLI.
 * Falls back to caller's fallback (via TpmTokenStore) if any command fails.
 */
public class MacKeychainTokenStore implements TokenStore {
    private static final String SERVICE = "Tungsten-Client";
    private static final String ACCOUNT = "tungsten-token";
    private static final String DATA_SERVICE_PREFIX = "Tungsten-Data-";

    @Override
    public void saveToken(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("token cannot be null or empty");
        }
        
        // Validate token doesn't contain dangerous characters
        if (token.contains("\n") || token.contains("\r")) {
            throw new IllegalArgumentException("token contains invalid characters");
        }
        
        // Ensure any previous item is removed to avoid duplicates
        run(new String[]{"security", "delete-generic-password", "-a", ACCOUNT, "-s", SERVICE}, false);
        // Add or update (-U) the secret
        run(new String[]{"security", "add-generic-password", "-a", ACCOUNT, "-s", SERVICE, "-w", token, "-U"}, true);
    }

    @Override
    public Optional<String> loadToken() throws Exception {
        CmdResult r = run(new String[]{"security", "find-generic-password", "-a", ACCOUNT, "-s", SERVICE, "-w"}, true);
        if (r.exitCode == 0) {
            String out = r.stdout.strip();
            if (!out.isEmpty()) return Optional.of(out);
        }
        return Optional.empty();
    }

    @Override
    public void clear() throws Exception {
        // Clear legacy single token
        run(new String[]{"security", "delete-generic-password", "-a", ACCOUNT, "-s", SERVICE}, false);
        
        // Clear all data entries by listing and deleting them
        // Note: macOS keychain doesn't have a wildcard delete, so we try to clear known keys
        // The loadAllData will help us identify what to clear
        Map<String, String> allData = loadAllData();
        for (String key : allData.keySet()) {
            clearData(key);
        }
    }

	@Override
	public void saveData(String key, String data) throws Exception {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("key cannot be null or empty");
        }
        if (data == null) {
            throw new IllegalArgumentException("data cannot be null");
        }

        // Validate data doesn't contain dangerous characters
        if (data.contains("\n") || data.contains("\r")) {
            throw new IllegalArgumentException("data contains invalid characters");
        }

        // Create a sanitized service name for this key
        String safeKey = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        String service = DATA_SERVICE_PREFIX + safeKey;

        // Ensure any previous item is removed to avoid duplicates
        run(new String[]{"security", "delete-generic-password", "-a", ACCOUNT, "-s", service}, false);
        
        // Add the data to keychain
        run(new String[]{"security", "add-generic-password", "-a", ACCOUNT, "-s", service, "-w", data, "-U"}, true);
    }

	@Override
	public Optional<String> loadData(String key) throws Exception {
        if (key == null || key.trim().isEmpty()) {
            return Optional.empty();
        }

        String safeKey = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        String service = DATA_SERVICE_PREFIX + safeKey;

        CmdResult r = run(new String[]{"security", "find-generic-password", "-a", ACCOUNT, "-s", service, "-w"}, true);
        if (r.exitCode == 0) {
            String out = r.stdout.strip();
            if (!out.isEmpty()) {
                return Optional.of(out);
            }
        }
        return Optional.empty();
    }

	@Override
	public void saveMultipleData(Map<String, String> dataMap) throws Exception {
        if (dataMap == null || dataMap.isEmpty()) {
            return;
        }

        for (Map.Entry<String, String> entry : dataMap.entrySet()) {
            saveData(entry.getKey(), entry.getValue());
        }
    }

	@Override
	public Map<String, String> loadAllData() throws Exception {
        Map<String, String> result = new HashMap<>();

        // Try to load known keys (from UserSessionManager constants)
        String[] knownKeys = {
            "tungsten.access_token",
            "tungsten.refresh_token",
            "tungsten.user_data",
            "tungsten.session_metadata"
        };

        for (String key : knownKeys) {
            Optional<String> value = loadData(key);
            if (value.isPresent()) {
                result.put(key.replaceAll("[^a-zA-Z0-9._-]", "_"), value.get());
            }
        }

        return result;
    }

	@Override
	public void clearData(String key) throws Exception {
        if (key == null || key.trim().isEmpty()) {
            return;
        }

        String safeKey = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        String service = DATA_SERVICE_PREFIX + safeKey;

        run(new String[]{"security", "delete-generic-password", "-a", ACCOUNT, "-s", service}, false);
    }

	private static CmdResult run(String[] cmd, boolean captureOut) throws IOException, InterruptedException {
        // Validate command arguments
        for (String arg : cmd) {
            if (arg == null) {
                throw new IllegalArgumentException("Command argument cannot be null");
            }
        }
        
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear(); // Clear environment variables for security
        
        Process p = pb.start();
        StringBuilder sbOut = new StringBuilder();
        StringBuilder sbErr = new StringBuilder();
        
        try {
            if (captureOut) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; 
                    while ((line = br.readLine()) != null) {
                        sbOut.append(line).append('\n');
                    }
                }
            } else {
                // Drain to avoid blocking
                Thread drainThread = new Thread(() -> {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        while (br.readLine() != null) {}
                    } catch (IOException ignored) {}
                });
                drainThread.setDaemon(true);
                drainThread.start();
            }
            
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line; 
                while ((line = br.readLine()) != null) {
                    sbErr.append(line).append('\n');
                }
            }
            
            int code = p.waitFor();
            return new CmdResult(code, sbOut.toString(), sbErr.toString());
        } finally {
            p.destroyForcibly();
        }
    }

    private record CmdResult(int exitCode, String stdout, String stderr) {}
}
