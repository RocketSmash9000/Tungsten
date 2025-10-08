package security;

import java.util.Map;
import java.util.Optional;

public interface TokenStore {
    void saveToken(String token) throws Exception;
    Optional<String> loadToken() throws Exception;
    void clear() throws Exception;

    // Enhanced methods for multi-key storage
    void saveData(String key, String data) throws Exception;
    Optional<String> loadData(String key) throws Exception;
    void saveMultipleData(Map<String, String> dataMap) throws Exception;
    Map<String, String> loadAllData() throws Exception;
    void clearData(String key) throws Exception;
}
