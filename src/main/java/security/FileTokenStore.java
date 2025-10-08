package security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.CleartextKeysetHandle;
import com.google.crypto.tink.KeyTemplates;
import com.google.crypto.tink.JsonKeysetReader;
import com.google.crypto.tink.JsonKeysetWriter;
import util.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Stores tokens and user data encrypted-at-rest using Google Tink AEAD.
 * Supports both legacy single token storage and enhanced multi-key storage.
 * Keyset is generated on first use and stored under the config directory.
 */
public class FileTokenStore implements TokenStore {
    private static final byte[] ASSOCIATED_DATA = "tungsten-token-v1".getBytes(StandardCharsets.UTF_8);
    private static final String LEGACY_TOKEN_KEY = "legacy_token";

    private final Path keysetFile;
    private final Path tokenFile;
    private final Path dataFile;
    private final ObjectMapper objectMapper;

    public FileTokenStore(Path baseDir) throws Exception {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir cannot be null");
        }
        
        // Validate and normalize path to prevent directory traversal
        Path normalizedBase = baseDir.toAbsolutePath().normalize();
        String basePath = normalizedBase.toString();
        if (basePath.contains("../") || basePath.contains("..\\")) {
            throw new IllegalArgumentException("Invalid base directory path");
        }
        
        AeadConfig.register();
        this.keysetFile = normalizedBase.resolve("keys.json");
        this.tokenFile = normalizedBase.resolve("secret").resolve("token.enc");
        this.dataFile = normalizedBase.resolve("secret").resolve("data.enc");
        this.objectMapper = new ObjectMapper();

        // Ensure dirs with restricted permissions
        Files.createDirectories(this.tokenFile.getParent());
        // Set restrictive permissions on secret directory (Unix-like systems)
        try {
            Files.setPosixFilePermissions(this.tokenFile.getParent(), 
                java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows or other systems that don't support POSIX permissions
        }
    }

    @Override
    public void saveToken(String token) throws Exception {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Token cannot be null or empty");
        }
        
        Logger.debug("Saving legacy token to encrypted storage");

        Aead aead = getOrCreateAead();
        byte[] ciphertext = aead.encrypt(token.getBytes(StandardCharsets.UTF_8), ASSOCIATED_DATA);

        Files.write(tokenFile, ciphertext,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);

        // Set restrictive permissions on the token file
        try {
            Files.setPosixFilePermissions(tokenFile, 
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows or other systems
        }

        Logger.debug("Legacy token saved successfully");
    }

    @Override
    public Optional<String> loadToken() throws Exception {
        Logger.debug("Loading legacy token from encrypted storage");

        if (!Files.exists(tokenFile)) {
            Logger.debug("No legacy token file found");
            return Optional.empty();
        }

        try {
            Aead aead = getOrCreateAead();
            byte[] ciphertext = Files.readAllBytes(tokenFile);
            byte[] plaintext = aead.decrypt(ciphertext, ASSOCIATED_DATA);
            String token = new String(plaintext, StandardCharsets.UTF_8);

            Logger.debug("Legacy token loaded successfully");
            return Optional.of(token);
        } catch (Exception e) {
            Logger.warn("Failed to decrypt legacy token: " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void clear() throws Exception {
        Logger.debug("Clearing all stored data");

        try {
            Files.deleteIfExists(tokenFile);
            Files.deleteIfExists(dataFile);
            Logger.debug("All stored data cleared successfully");
        } catch (IOException e) {
            Logger.error("Failed to clear stored data: " + e.getMessage());
            throw e;
        }
    }

    @Override
    public void saveData(String key, String data) throws Exception {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }

        Logger.debug("Saving data for key: " + key);

        // Load existing data
        Map<String, String> allData = loadAllData();
        allData.put(key, data);

        // Save updated data
        saveMultipleData(allData);

        Logger.debug("Data saved successfully for key: " + key);
    }

    @Override
    public Optional<String> loadData(String key) throws Exception {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }

        Logger.debug("Loading data for key: " + key);

        Map<String, String> allData = loadAllData();
        String data = allData.get(key);

        if (data != null) {
            Logger.debug("Data loaded successfully for key: " + key);
            return Optional.of(data);
        } else {
            Logger.debug("No data found for key: " + key);
            return Optional.empty();
        }
    }

    @Override
    public void saveMultipleData(Map<String, String> dataMap) throws Exception {
        if (dataMap == null) {
            throw new IllegalArgumentException("Data map cannot be null");
        }

        Logger.debug("Saving multiple data entries: " + dataMap.size() + " items");

        Aead aead = getOrCreateAead();
        String jsonData = objectMapper.writeValueAsString(dataMap);
        byte[] ciphertext = aead.encrypt(jsonData.getBytes(StandardCharsets.UTF_8), ASSOCIATED_DATA);

        Files.write(dataFile, ciphertext,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);

        // Set restrictive permissions on the data file
        try {
            Files.setPosixFilePermissions(dataFile,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows or other systems
        }

        Logger.debug("Multiple data entries saved successfully");
    }

    @Override
    public Map<String, String> loadAllData() throws Exception {
        Logger.debug("Loading all data from encrypted storage");

        if (!Files.exists(dataFile)) {
            Logger.debug("No data file found, returning empty map");
            return new HashMap<>();
        }

        try {
            Aead aead = getOrCreateAead();
            byte[] ciphertext = Files.readAllBytes(dataFile);
            byte[] plaintext = aead.decrypt(ciphertext, ASSOCIATED_DATA);
            String jsonData = new String(plaintext, StandardCharsets.UTF_8);

            @SuppressWarnings("unchecked")
            Map<String, String> dataMap = objectMapper.readValue(jsonData, Map.class);

            Logger.debug("All data loaded successfully: " + dataMap.size() + " items");
            return dataMap != null ? dataMap : new HashMap<>();
        } catch (Exception e) {
            Logger.warn("Failed to decrypt data file: " + e.getMessage());
            return new HashMap<>();
        }
    }

    @Override
    public void clearData(String key) throws Exception {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or empty");
        }

        Logger.debug("Clearing data for key: " + key);

        Map<String, String> allData = loadAllData();
        if (allData.remove(key) != null) {
            saveMultipleData(allData);
            Logger.debug("Data cleared successfully for key: " + key);
        } else {
            Logger.debug("No data found to clear for key: " + key);
        }
    }

    private Aead getOrCreateAead() throws Exception {
        KeysetHandle keysetHandle;

        if (Files.exists(keysetFile)) {
            // Load existing keyset
            try (InputStream inputStream = Files.newInputStream(keysetFile)) {
                keysetHandle = CleartextKeysetHandle.read(JsonKeysetReader.withInputStream(inputStream));
                Logger.debug("Loaded existing encryption keyset");
            }
        } else {
            // Generate new keyset
            keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES128_GCM"));

            // Save keyset
            try (OutputStream outputStream = Files.newOutputStream(keysetFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                CleartextKeysetHandle.write(keysetHandle, JsonKeysetWriter.withOutputStream(outputStream));
                Logger.info("Generated new encryption keyset");
            }

            // Set restrictive permissions on keyset file
            try {
                Files.setPosixFilePermissions(keysetFile,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows or other systems
            }
        }

        return keysetHandle.getPrimitive(Aead.class);
    }
}
