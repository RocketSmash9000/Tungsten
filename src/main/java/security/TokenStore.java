package security;

import java.util.Optional;

public interface TokenStore {
    void saveToken(String token) throws Exception;
    Optional<String> loadToken() throws Exception;
    void clear() throws Exception;
}
