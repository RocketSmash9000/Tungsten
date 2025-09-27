package security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
        if (token == null) throw new IllegalArgumentException("token is null");
        // Use a label and two attributes to identify the secret deterministically
        // echo -n "$token" | secret-tool store --label="Tungsten Client" service Tungsten-Client account tungsten-token
        ProcessBuilder pb = new ProcessBuilder("bash", "-lc",
                "printf %s '" + escapeSingleQuotes(token) + "' | secret-tool store --label='Tungsten Client' service '" + SERVICE + "' account '" + ACCOUNT + "'");
        run(pb, true);
    }

    @Override
    public Optional<String> loadToken() throws Exception {
        // secret-tool lookup service 'Tungsten-Client' account 'tungsten-token'
        ProcessBuilder pb = new ProcessBuilder("bash", "-lc",
                "secret-tool lookup service '" + SERVICE + "' account '" + ACCOUNT + "'");
        CmdResult r = run(pb, true);
        if (r.exitCode == 0) {
            String out = r.stdout;
            if (out != null && !out.isBlank()) {
                return Optional.of(out.strip());
            }
        }
        return Optional.empty();
    }

    @Override
    public void clear() throws Exception {
        // secret-tool clear service 'Tungsten-Client' account 'tungsten-token'
        ProcessBuilder pb = new ProcessBuilder("bash", "-lc",
                "secret-tool clear service '" + SERVICE + "' account '" + ACCOUNT + "'");
        run(pb, false);
    }

    private static String escapeSingleQuotes(String s) {
        return s.replace("'", "'\\''");
    }

    private static CmdResult run(ProcessBuilder pb, boolean captureOut) throws IOException, InterruptedException {
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
