package security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import util.Logger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * UserSessionManager handles secure storage and management of user authentication data
 * including tokens, user information, and session metadata.
 *
 * This class integrates with the existing TokenVault system to provide encrypted
 * storage of sensitive user data across different platforms.
 */
public class UserSessionManager {
    private static final String ACCESS_TOKEN_KEY = "tungsten.access_token";
    private static final String REFRESH_TOKEN_KEY = "tungsten.refresh_token";
    private static final String USER_DATA_KEY = "tungsten.user_data";
    private static final String SESSION_METADATA_KEY = "tungsten.session_metadata";

    private final ObjectMapper objectMapper;
    private final TokenVault tokenVault;

    public UserSessionManager() {
        this.objectMapper = new ObjectMapper();
        this.tokenVault = TokenVault.get();
    }

    /**
     * Stores user authentication data securely including tokens and user information
     */
    public void storeUserSession(String accessToken, String refreshToken, UserData userData) {
        try {
            Logger.info("Storing user session for user: " + userData.getUsername() + " (ID: " + userData.getUserId() + ")");

            // Create a comprehensive data map for secure storage
            Map<String, String> sessionData = new HashMap<>();

            // Store tokens
            sessionData.put(ACCESS_TOKEN_KEY, accessToken);
            sessionData.put(REFRESH_TOKEN_KEY, refreshToken);
            Logger.debug("Tokens prepared for secure storage");

            // Store user data as JSON
            String userDataJson = objectMapper.writeValueAsString(userData);
            sessionData.put(USER_DATA_KEY, userDataJson);
            Logger.debug("User data serialized for storage");

            // Store session metadata
            SessionMetadata metadata = new SessionMetadata(
                Instant.now().toString(),
                System.getProperty("os.name"),
                System.getProperty("os.version"),
                tokenVault.getStoreType()
            );
            String metadataJson = objectMapper.writeValueAsString(metadata);
            sessionData.put(SESSION_METADATA_KEY, metadataJson);
            Logger.debug("Session metadata prepared");

            // Store all data securely using enhanced TokenVault
            tokenVault.saveMultipleData(sessionData);

            Logger.info("User session stored successfully for " + userData.getUsername());
            logAuthEvent(AuthEvent.LOGIN_SUCCESS, userData.getUsername(),
                "Session stored with " + tokenVault.getStoreType() + " encryption");

        } catch (Exception e) {
            Logger.error("Failed to store user session: " + e.getMessage());
            logAuthEvent(AuthEvent.SECURITY_VIOLATION, userData.getUsername(),
                "Session storage failed: " + e.getMessage());
            throw new RuntimeException("Session storage failed", e);
        }
    }

    /**
     * Loads the stored user session data
     */
    public Optional<UserSession> loadUserSession() {
        try {
            Logger.debug("Loading user session from secure storage");

            Map<String, String> sessionData = tokenVault.loadAllData();

            if (sessionData.isEmpty() ||
                !sessionData.containsKey(ACCESS_TOKEN_KEY) ||
                !sessionData.containsKey(REFRESH_TOKEN_KEY) ||
                !sessionData.containsKey(USER_DATA_KEY)) {
                Logger.debug("No complete user session found in storage");
                return Optional.empty();
            }

            String accessToken = sessionData.get(ACCESS_TOKEN_KEY);
            String refreshToken = sessionData.get(REFRESH_TOKEN_KEY);

            UserData userData = objectMapper.readValue(sessionData.get(USER_DATA_KEY), UserData.class);

            SessionMetadata metadata = null;
            if (sessionData.containsKey(SESSION_METADATA_KEY)) {
                try {
                    metadata = objectMapper.readValue(sessionData.get(SESSION_METADATA_KEY), SessionMetadata.class);
                } catch (Exception e) {
                    Logger.warn("Failed to parse session metadata: " + e.getMessage());
                }
            }

            UserSession session = new UserSession(accessToken, refreshToken, userData, metadata);

            Logger.info("User session loaded successfully for " + userData.getUsername());
            return Optional.of(session);

        } catch (Exception e) {
            Logger.error("Failed to load user session: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Updates only the access token (used after token refresh)
     */
    public void updateAccessToken(String newAccessToken) {
        try {
            Logger.debug("Updating access token");

            // Update just the access token
            tokenVault.saveData(ACCESS_TOKEN_KEY, newAccessToken);

            // Update last refresh time in metadata
            Optional<String> metadataJson = tokenVault.loadData(SESSION_METADATA_KEY);
            if (metadataJson.isPresent()) {
                SessionMetadata metadata = objectMapper.readValue(metadataJson.get(), SessionMetadata.class);
                metadata.setLastTokenRefresh(Instant.now().toString());
                String updatedMetadataJson = objectMapper.writeValueAsString(metadata);
                tokenVault.saveData(SESSION_METADATA_KEY, updatedMetadataJson);
            }

            Logger.debug("Access token updated successfully");
            logAuthEvent(AuthEvent.TOKEN_REFRESHED, "system", "Access token refreshed");

        } catch (Exception e) {
            Logger.error("Failed to update access token: " + e.getMessage());
            logAuthEvent(AuthEvent.TOKEN_REFRESH_FAILED, "system", "Token refresh failed: " + e.getMessage());
            throw new RuntimeException("Token update failed", e);
        }
    }

    /**
     * Updates user data (used after profile changes or email verification)
     */
    public void updateUserData(UserData userData) {
        try {
            Logger.info("Updating user data for " + userData.getUsername());
            String userDataJson = objectMapper.writeValueAsString(userData);
            tokenVault.saveData(USER_DATA_KEY, userDataJson);
            Logger.debug("User data updated successfully");

            logAuthEvent(AuthEvent.EMAIL_VERIFIED, userData.getUsername(),
                "User data updated - Email verified: " + userData.isEmailVerified());

        } catch (Exception e) {
            Logger.error("Failed to update user data: " + e.getMessage());
            throw new RuntimeException("User data update failed", e);
        }
    }

    /**
     * Clears all stored session data (used during logout)
     */
    public void clearSession() {
        try {
            Optional<UserSession> session = loadUserSession();
            String username = session.map(s -> s.getUserData().getUsername()).orElse("unknown");

            Logger.info("==================== LOGOUT INITIATED ====================");
            Logger.info("Clearing user session data for " + username);
            Logger.info("- Access token will be invalidated");
            Logger.info("- Refresh token will be revoked");
            Logger.info("- All encrypted local data will be cleared");
            Logger.info("- User will need a NEW private key for next login");
            Logger.info("  (Private keys are single-use and expire in 7 days if unused)");

            // Clear all session data
            tokenVault.clear();

            Logger.info("User session cleared successfully");
            Logger.info("==================== LOGOUT COMPLETE ====================");
            logAuthEvent(AuthEvent.LOGOUT, username, "Session data cleared from secure storage");

        } catch (Exception e) {
            Logger.error("Failed to clear session data: " + e.getMessage());
            // Don't throw exception here as this might be called during cleanup
        }
    }

    /**
     * Logs authentication events for security monitoring
     */
    public void logAuthEvent(AuthEvent event, String username, String additionalInfo) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        String logMessage = String.format("AUTH_EVENT [%s] User: %s, Event: %s, Info: %s, Store: %s",
            timestamp, username, event.name(), additionalInfo, tokenVault.getStoreType());

        switch (event) {
            case LOGIN_SUCCESS:
            case REGISTRATION_SUCCESS:
            case EMAIL_VERIFIED:
            case PASSWORD_CHANGED:
            case TOKEN_REFRESHED:
                Logger.info(logMessage);
                break;
            case LOGIN_FAILED:
            case REGISTRATION_FAILED:
            case EMAIL_VERIFICATION_FAILED:
            case TOKEN_REFRESH_FAILED:
                Logger.warn(logMessage);
                break;
            case LOGOUT:
            case SESSION_EXPIRED:
                Logger.info(logMessage);
                break;
            case SECURITY_VIOLATION:
            case INVALID_TOKEN:
                Logger.error(logMessage);
                break;
            default:
                Logger.debug(logMessage);
        }
    }

    /**
     * Gets session statistics for monitoring
     */
    public Optional<SessionStats> getSessionStats() {
        try {
            Optional<UserSession> session = loadUserSession();
            if (session.isEmpty()) {
                return Optional.empty();
            }

            SessionMetadata metadata = session.get().getMetadata();
            if (metadata == null) {
                return Optional.empty();
            }

            SessionStats stats = new SessionStats(
                metadata.getLoginTime(),
                metadata.getLastTokenRefresh(),
                metadata.getOperatingSystem(),
                metadata.getStoreType(),
                session.get().getUserData().getUsername(),
                session.get().getUserData().isEmailVerified()
            );

            return Optional.of(stats);

        } catch (Exception e) {
            Logger.error("Failed to get session stats: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Validates if a session exists and is properly structured
     */
    public boolean isValidSession() {
        try {
            Optional<UserSession> session = loadUserSession();
            if (session.isEmpty()) {
                Logger.debug("No session found");
                return false;
            }

            UserSession userSession = session.get();
            boolean isValid = userSession.getAccessToken() != null &&
                             !userSession.getAccessToken().trim().isEmpty() &&
                             userSession.getRefreshToken() != null &&
                             !userSession.getRefreshToken().trim().isEmpty() &&
                             userSession.getUserData() != null &&
                             userSession.getUserData().getUserId() > 0;

            Logger.debug("Session validation result: " + isValid);
            return isValid;

        } catch (Exception e) {
            Logger.warn("Session validation failed: " + e.getMessage());
            return false;
        }
    }

    // Inner classes for data structures
    public static class UserData {
        private int userId;
        private String username;
        private String email;
        private boolean emailVerified;
        private String createdAt;
        private String lastLogin;

        // Default constructor for Jackson
        public UserData() {}

        public UserData(int userId, String username, String email, boolean emailVerified, String createdAt, String lastLogin) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.emailVerified = emailVerified;
            this.createdAt = createdAt;
            this.lastLogin = lastLogin;
        }

        // Getters and setters
        public int getUserId() { return userId; }
        public void setUserId(int userId) { this.userId = userId; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public boolean isEmailVerified() { return emailVerified; }
        public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }

        public String getCreatedAt() { return createdAt; }
        public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

        public String getLastLogin() { return lastLogin; }
        public void setLastLogin(String lastLogin) { this.lastLogin = lastLogin; }
    }

    public static class SessionMetadata {
        private String loginTime;
        private String lastTokenRefresh;
        private String operatingSystem;
        private String osVersion;
        private String storeType;

        public SessionMetadata() {}

        public SessionMetadata(String loginTime, String operatingSystem, String osVersion, String storeType) {
            this.loginTime = loginTime;
            this.lastTokenRefresh = loginTime; // Initially same as login time
            this.operatingSystem = operatingSystem;
            this.osVersion = osVersion;
            this.storeType = storeType;
        }

        // Getters and setters
        public String getLoginTime() { return loginTime; }
        public void setLoginTime(String loginTime) { this.loginTime = loginTime; }

        public String getLastTokenRefresh() { return lastTokenRefresh; }
        public void setLastTokenRefresh(String lastTokenRefresh) { this.lastTokenRefresh = lastTokenRefresh; }

        public String getOperatingSystem() { return operatingSystem; }
        public void setOperatingSystem(String operatingSystem) { this.operatingSystem = operatingSystem; }

        public String getOsVersion() { return osVersion; }
        public void setOsVersion(String osVersion) { this.osVersion = osVersion; }

        public String getStoreType() { return storeType; }
        public void setStoreType(String storeType) { this.storeType = storeType; }
    }

    public static class UserSession {
        private String accessToken;
        private String refreshToken;
        private UserData userData;
        private SessionMetadata metadata;

        public UserSession() {}

        public UserSession(String accessToken, String refreshToken, UserData userData, SessionMetadata metadata) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.userData = userData;
            this.metadata = metadata;
        }

        // Getters and setters
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

        public UserData getUserData() { return userData; }
        public void setUserData(UserData userData) { this.userData = userData; }

        public SessionMetadata getMetadata() { return metadata; }
        public void setMetadata(SessionMetadata metadata) { this.metadata = metadata; }
    }

    public static class SessionStats {
        private String loginTime;
        private String lastTokenRefresh;
        private String operatingSystem;
        private String storeType;
        private String username;
        private boolean emailVerified;

        public SessionStats(String loginTime, String lastTokenRefresh, String operatingSystem,
                          String storeType, String username, boolean emailVerified) {
            this.loginTime = loginTime;
            this.lastTokenRefresh = lastTokenRefresh;
            this.operatingSystem = operatingSystem;
            this.storeType = storeType;
            this.username = username;
            this.emailVerified = emailVerified;
        }

        // Getters
        public String getLoginTime() { return loginTime; }
        public String getLastTokenRefresh() { return lastTokenRefresh; }
        public String getOperatingSystem() { return operatingSystem; }
        public String getStoreType() { return storeType; }
        public String getUsername() { return username; }
        public boolean isEmailVerified() { return emailVerified; }
    }

    public enum AuthEvent {
        LOGIN_SUCCESS,
        LOGIN_FAILED,
        REGISTRATION_SUCCESS,
        REGISTRATION_FAILED,
        EMAIL_VERIFIED,
        EMAIL_VERIFICATION_FAILED,
        TOKEN_REFRESHED,
        TOKEN_REFRESH_FAILED,
        PASSWORD_CHANGED,
        LOGOUT,
        SESSION_EXPIRED,
        SECURITY_VIOLATION,
        INVALID_TOKEN
    }
}
