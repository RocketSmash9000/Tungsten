package security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stores the token in the Linux Secret Service (libsecret) via the `secret-tool` CLI.
 * This requires a desktop keyring (e.g., GNOME Keyring or KWallet) to be available and unlocked.
 * Falls back to caller's fallback (via TpmTokenStore) if any command fails.
 */
public class LinuxSecretServiceTokenStore implements TokenStore {
    private static final String SERVICE = "Tungsten-Client";
    private static final String ACCOUNT = "tungsten-token";

    @Override
    public void saveToken(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("token cannot be null or empty");
        }
        
        // Use ProcessBuilder with separate arguments to avoid shell injection
        ProcessBuilder pb = new ProcessBuilder(
            "secret-tool", "store", 
            "--label", "Tungsten Client",
            "service", SERVICE,
            "account", ACCOUNT
        );
        
        Process process = pb.start();
        try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(token);
            writer.flush();
        }
        
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("secret-tool store failed with exit code: " + exitCode);
        }
    }

    @Override
    public Optional<String> loadToken() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "secret-tool", "lookup",
            "service", SERVICE,
            "account", ACCOUNT
        );
        
        CmdResult r = run(pb, true);
        if (r.exitCode == 0) {
            String out = r.stdout;
            if (out != null && !out.trim().isEmpty()) {
                return Optional.of(out.trim());
            }
        }
        return Optional.empty();
    }

    @Override
    public void clear() throws Exception {
        // Clear legacy single token
        ProcessBuilder pb = new ProcessBuilder(
            "secret-tool", "clear",
            "service", SERVICE,
            "account", ACCOUNT
        );
        run(pb, false);
        
        // Clear all data entries
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

        // Create a sanitized account name for this key
        String safeKey = key.replaceAll("[^a-zA-Z0-9._-]", "_");

        ProcessBuilder pb = new ProcessBuilder(
            "secret-tool", "store",
            "--label", "Tungsten Client Data: " + safeKey,
            "service", SERVICE,
            "account", safeKey,
            "datakey", key
        );

        Process process = pb.start();
        try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(data);
            writer.flush();
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("secret-tool store failed for key '" + key + "' with exit code: " + exitCode);
        }
    }

	@Override
	public Optional<String> loadData(String key) throws Exception {
		if (key == null || key.trim().isEmpty()) {
            return Optional.empty();
        }

        String safeKey = key.replaceAll("[^a-zA-Z0-9._-]", "_");

        ProcessBuilder pb = new ProcessBuilder(
            "secret-tool", "lookup",
            "service", SERVICE,
            "account", safeKey
        );

        CmdResult r = run(pb, true);
        if (r.exitCode == 0) {
            String out = r.stdout;
            if (out != null && !out.trim().isEmpty()) {
                return Optional.of(out.trim());
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

        ProcessBuilder pb = new ProcessBuilder(
            "secret-tool", "clear",
            "service", SERVICE,
            "account", safeKey
        );
        run(pb, false);
	}

	private static CmdResult run(ProcessBuilder pb, boolean captureOut) throws IOException, InterruptedException {
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
