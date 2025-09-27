package security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Stores the token in macOS Keychain using the `security` CLI.
 * Falls back to caller's fallback (via TpmTokenStore) if any command fails.
 */
public class MacKeychainTokenStore implements TokenStore {
    private static final String SERVICE = "Tungsten-Client";
    private static final String ACCOUNT = "tungsten-token";

    @Override
    public void saveToken(String token) throws Exception {
        if (token == null) throw new IllegalArgumentException("token is null");
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
        run(new String[]{"security", "delete-generic-password", "-a", ACCOUNT, "-s", SERVICE}, false);
    }

    private static CmdResult run(String[] cmd, boolean captureOut) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process p = pb.start();
        StringBuilder sbOut = new StringBuilder();
        StringBuilder sbErr = new StringBuilder();
        if (captureOut) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = br.readLine()) != null) sbOut.append(line).append('\n');
            }
        } else {
            // Drain to avoid blocking
            new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    while (br.readLine() != null) {}
                } catch (IOException ignored) {}
            }).start();
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line; while ((line = br.readLine()) != null) sbErr.append(line).append('\n');
        }
        int code = p.waitFor();
        return new CmdResult(code, sbOut.toString(), sbErr.toString());
    }

    private record CmdResult(int exitCode, String stdout, String stderr) {}
}
