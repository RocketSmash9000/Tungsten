package security;

import com.sun.jna.platform.win32.Crypt32Util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Windows DPAPI-backed token storage for the current user.
 * The token is protected using the user's credential context and stored as an encrypted blob on disk.
 */
public class WindowsDpapiTokenStore implements TokenStore {
    // Use CRYPTPROTECT_UI_FORBIDDEN to prevent UI prompts
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    private final Path tokenFile;
    private final Path dataDir;

    public WindowsDpapiTokenStore(Path baseDir) throws Exception {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir cannot be null");
        }
        
        // Validate and normalize path
        Path normalizedBase = baseDir.toAbsolutePath().normalize();
        String basePath = normalizedBase.toString();
        if (basePath.contains("../") || basePath.contains("..\\")) {
            throw new IllegalArgumentException("Invalid base directory path");
        }
        
        this.tokenFile = normalizedBase.resolve("secret").resolve("token.dpapi");
        this.dataDir = normalizedBase.resolve("secret").resolve("data");
        Files.createDirectories(this.tokenFile.getParent());
        Files.createDirectories(this.dataDir);
    }

    @Override
    public void saveToken(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("token cannot be null or empty");
        }
        
        byte[] plaintext = token.getBytes(StandardCharsets.UTF_8);
        // Protect for current user with UI forbidden
        byte[] protectedBlob = Crypt32Util.cryptProtectData(
            plaintext, 
            CRYPTPROTECT_UI_FORBIDDEN
        );
        
        // Atomic write operation
        Path tempFile = tokenFile.getParent().resolve(tokenFile.getFileName() + ".tmp");
        Files.write(tempFile, protectedBlob,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        
        // Atomic move
        Files.move(tempFile, tokenFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
        // Clear legacy single token file
        if (Files.exists(tokenFile)) {
            try {
                // Secure overwrite with random data
                long fileSize = Files.size(tokenFile);
                if (fileSize > 0 && fileSize < Integer.MAX_VALUE) {
                    SecureRandom random = new SecureRandom();
                    byte[] randomData = new byte[(int) fileSize];
                    random.nextBytes(randomData);
                    Files.write(tokenFile, randomData, StandardOpenOption.WRITE);
                }
            } catch (Exception ignored) {}
            Files.deleteIfExists(tokenFile);
        }
        
        // Clear all data files
        if (Files.exists(dataDir)) {
            try (var stream = Files.list(dataDir)) {
                stream.filter(p -> p.toString().endsWith(".dpapi"))
                        .forEach(p -> {
                            try {
                                // Secure overwrite with random data
                                long fileSize = Files.size(p);
                                if (fileSize > 0 && fileSize < Integer.MAX_VALUE) {
                                    SecureRandom random = new SecureRandom();
                                    byte[] randomData = new byte[(int) fileSize];
                                    random.nextBytes(randomData);
                                    Files.write(p, randomData, StandardOpenOption.WRITE);
                                }
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {}
                        });
            } catch (Exception ignored) {}
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

        // Sanitize key to create safe filename
        String safeKey = key.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path keyFile = dataDir.resolve(safeKey + ".dpapi");

        byte[] plaintext = data.getBytes(StandardCharsets.UTF_8);
        byte[] protectedBlob = Crypt32Util.cryptProtectData(plaintext, CRYPTPROTECT_UI_FORBIDDEN);

        // Atomic write
        Path tempFile = keyFile.getParent().resolve(keyFile.getFileName() + ".tmp");
        Files.write(tempFile, protectedBlob,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.move(tempFile, keyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	@Override
	public Optional<String> loadData(String key) throws Exception {
		if (key == null || key.trim().isEmpty()) {
			return Optional.empty();
		}

		String safeKey = key.replaceAll("[^a-zA-Z0-9._-]", "_");
		Path keyFile = dataDir.resolve(safeKey + ".dpapi");

		if (!Files.exists(keyFile)) {
			return Optional.empty();
		}

		try {
			byte[] protectedBlob = Files.readAllBytes(keyFile);
			byte[] plaintext = Crypt32Util.cryptUnprotectData(protectedBlob);
			return Optional.of(new String(plaintext, StandardCharsets.UTF_8));
		} catch (Throwable t) {
			return Optional.empty();
		}
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

		if (!Files.exists(dataDir)) {
			return result;
		}

		try (var stream = Files.list(dataDir)) {
			stream.filter(p -> p.toString().endsWith(".dpapi"))
					.forEach(p -> {
						try {
							String fileName = p.getFileName().toString();
							String key = fileName.substring(0, fileName.length() - 6); // Remove .dpapi
							byte[] protectedBlob = Files.readAllBytes(p);
							byte[] plaintext = Crypt32Util.cryptUnprotectData(protectedBlob);
							result.put(key, new String(plaintext, StandardCharsets.UTF_8));
						} catch (Exception ignored) {
							// Skip corrupted files
						}
					});
		}

		return result;
	}

	@Override
	public void clearData(String key) throws Exception {
		if (key == null || key.trim().isEmpty()) {
			return;
		}

		String safeKey = key.replaceAll("[^a-zA-Z0-9._-]", "_");
		Path keyFile = dataDir.resolve(safeKey + ".dpapi");

		if (Files.exists(keyFile)) {
			try {
				// Secure overwrite with random data
				long fileSize = Files.size(keyFile);
				if (fileSize > 0 && fileSize < Integer.MAX_VALUE) {
					SecureRandom random = new SecureRandom();
					byte[] randomData = new byte[(int) fileSize];
					random.nextBytes(randomData);
					Files.write(keyFile, randomData, StandardOpenOption.WRITE);
				}
			} catch (Exception ignored) {}
			Files.deleteIfExists(keyFile);
		}
	}
}
