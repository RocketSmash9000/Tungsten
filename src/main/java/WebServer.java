import config.Config;
import util.Logger;
import spark.Spark;
import java.nio.file.Files;
import java.nio.file.Paths;
import security.TokenVault;
import security.UserSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

import static config.Config.configDir;

public class WebServer {
	private static UserSessionManager sessionManager;
	private static final ObjectMapper objectMapper = new ObjectMapper();

	@SuppressWarnings("D")
	public static void main(String[] args) {
		// Resolve a cross-platform config directory
		String baseConfigDir = System.getenv("APPDATA");
		if (baseConfigDir == null || baseConfigDir.isEmpty()) {
			String xdg = System.getenv("XDG_CONFIG_HOME");
			if (xdg != null && !xdg.isEmpty()) {
				baseConfigDir = xdg;
			} else {
				String home = System.getenv("HOME");
				if (home != null && !home.isEmpty()) {
					baseConfigDir = home + "/.config";
				}
			}
		}

		String separator = java.io.File.separator;
		configDir = (baseConfigDir != null && !baseConfigDir.isEmpty())
				? baseConfigDir + separator + "Tungsten-client"
				: "Tungsten-client";
		Config.set("System.baseDir", configDir);

		// Ensure config dir exists
		try {
			Files.createDirectories(Paths.get(configDir));
		} catch (Exception ignored) {}

		// Initialize secure token vault (TPM if available, else encrypted file store)
		try {
			TokenVault.init(Paths.get(configDir));
			Logger.info("Token store initialized: " + TokenVault.get().getStoreType());
		} catch (Exception e) {
			Logger.error("Failed to initialize token vault: " + e.getMessage());
		}

		// Initialize session manager
		try {
			sessionManager = new UserSessionManager();
			Logger.info("User session manager initialized");
		} catch (Exception e) {
			Logger.error("Failed to initialize session manager: " + e.getMessage());
		}

		// Start web server
		startServer();
	}

	@SuppressWarnings("D")
	private static void startServer() {
		// Configure port
		int port = Config.getInt("Server.port", 8080);
		Spark.port(port);

  		// Static files served from src/main/resources/public
		Spark.staticFiles.location("/public");
		Spark.staticFiles.expireTime(600); // 10 minutes

		// Serve icons from classpath /icons at /icons/*
		Spark.get("/icons/*", (req, res) -> {
			String splat = (req.splat() != null && req.splat().length > 0) ? req.splat()[0] : "";
			String resourcePath = "/icons/" + splat;
			try (java.io.InputStream is = WebServer.class.getResourceAsStream(resourcePath)) {
				if (is == null) {
					res.status(404);
					return "";
				}
				byte[] bytes = is.readAllBytes();
				if (resourcePath.endsWith(".png")) res.type("image/png");
				else if (resourcePath.endsWith(".svg")) res.type("image/svg+xml");
				else res.type("application/octet-stream");
				return bytes;
			}
		});

		// Security headers
		Spark.before((req, res) -> {
			res.header("X-Content-Type-Options", "nosniff");
			res.header("X-Frame-Options", "DENY");
			res.header("Referrer-Policy", "no-referrer");
			res.header("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
			res.header("Content-Security-Policy",
					"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
			// Basic CORS for same-origin; adjust later if needed
			res.header("Access-Control-Allow-Origin", req.headers("Origin") != null ? req.headers("Origin") : "");
			res.header("Vary", "Origin");
			res.header("Access-Control-Allow-Credentials", "true");
		});

		// Preflight handler
		Spark.options("/*", (req, res) -> {
			String reqHeaders = req.headers("Access-Control-Request-Headers");
			String reqMethod = req.headers("Access-Control-Request-Method");
			if (reqHeaders != null) res.header("Access-Control-Allow-Headers", reqHeaders);
			if (reqMethod != null) res.header("Access-Control-Allow-Method", reqMethod);
			return "";
		});

		// Health check
		Spark.get("/health", (req, res) -> {
			res.type("application/json");
			var map = new java.util.LinkedHashMap<String, Object>();
			map.put("status", "ok");
			map.put("time", java.time.ZonedDateTime.now().toString());
			return objectMapper.writeValueAsString(map);
		});

		// Enhanced health check with session info
		Spark.get("/v1/health", (req, res) -> {
			res.type("application/json");
			var map = new java.util.LinkedHashMap<String, Object>();
			map.put("status", "healthy");
			map.put("timestamp", java.time.ZonedDateTime.now().toString());
			map.put("version", "1.0.0");
			map.put("api_version", "v1");

			// Add session manager status
			if (sessionManager != null) {
				Optional<UserSessionManager.SessionStats> stats = sessionManager.getSessionStats();
				if (stats.isPresent()) {
					map.put("user_session", Map.of(
						"active", true,
						"username", stats.get().getUsername(),
						"store_type", stats.get().getStoreType(),
						"login_time", stats.get().getLoginTime(),
						"email_verified", stats.get().isEmailVerified()
					));
				} else {
					map.put("user_session", Map.of("active", false));
				}
			}

			return objectMapper.writeValueAsString(map);
		});

		// Local Session Management Endpoints

		// Store authentication data locally
		Spark.post("/api/session/store", (req, res) -> {
			res.type("application/json");
			try {
				JsonNode json = objectMapper.readTree(req.body());

				String accessToken = json.get("access_token").asText();
				String refreshToken = json.get("refresh_token").asText();
				int userId = json.get("user_id").asInt();
				String username = json.get("username").asText();
				String email = json.get("email").asText();
				boolean emailVerified = json.has("email_verified") ? json.get("email_verified").asBoolean() : false;
				String createdAt = json.has("created_at") ? json.get("created_at").asText() : "";
				String lastLogin = json.has("last_login") ? json.get("last_login").asText() : "";

				UserSessionManager.UserData userData = new UserSessionManager.UserData(
					userId, username, email, emailVerified, createdAt, lastLogin
				);

				sessionManager.storeUserSession(accessToken, refreshToken, userData);

				Logger.info("Session stored locally for user: " + username);

				return objectMapper.writeValueAsString(Map.of(
					"status", "success",
					"message", "Session stored securely",
					"store_type", TokenVault.get().getStoreType()
				));

			} catch (Exception e) {
				Logger.error("Failed to store session: " + e.getMessage());
				res.status(500);
				return objectMapper.writeValueAsString(Map.of(
					"error", "storage_failed",
					"message", "Failed to store session data"
				));
			}
		});

		// Load stored authentication data
		Spark.get("/api/session/load", (req, res) -> {
			res.type("application/json");
			try {
				Optional<UserSessionManager.UserSession> session = sessionManager.loadUserSession();

				if (session.isEmpty()) {
					return objectMapper.writeValueAsString(Map.of(
						"status", "no_session",
						"message", "No stored session found"
					));
				}

				UserSessionManager.UserSession userSession = session.get();
				UserSessionManager.UserData userData = userSession.getUserData();

				Map<String, Object> response = new HashMap<>();
				response.put("status", "success");
				response.put("access_token", userSession.getAccessToken());
				response.put("refresh_token", userSession.getRefreshToken());
				response.put("user_data", Map.of(
					"user_id", userData.getUserId(),
					"username", userData.getUsername(),
					"email", userData.getEmail(),
					"email_verified", userData.isEmailVerified(),
					"created_at", userData.getCreatedAt(),
					"last_login", userData.getLastLogin()
				));

				if (userSession.getMetadata() != null) {
					response.put("metadata", Map.of(
						"login_time", userSession.getMetadata().getLoginTime(),
						"last_token_refresh", userSession.getMetadata().getLastTokenRefresh(),
						"store_type", userSession.getMetadata().getStoreType()
					));
				}

				Logger.debug("Session loaded for user: " + userData.getUsername());
				return objectMapper.writeValueAsString(response);

			} catch (Exception e) {
				Logger.error("Failed to load session: " + e.getMessage());
				res.status(500);
				return objectMapper.writeValueAsString(Map.of(
					"error", "load_failed",
					"message", "Failed to load session data"
				));
			}
		});

		// Update access token
		Spark.post("/api/session/update-token", (req, res) -> {
			res.type("application/json");
			try {
				JsonNode json = objectMapper.readTree(req.body());
				String newAccessToken = json.get("access_token").asText();

				sessionManager.updateAccessToken(newAccessToken);

				return objectMapper.writeValueAsString(Map.of(
					"status", "success",
					"message", "Access token updated"
				));

			} catch (Exception e) {
				Logger.error("Failed to update access token: " + e.getMessage());
				res.status(500);
				return objectMapper.writeValueAsString(Map.of(
					"error", "update_failed",
					"message", "Failed to update access token"
				));
			}
		});

		// Update user data
		Spark.post("/api/session/update-user", (req, res) -> {
			res.type("application/json");
			try {
				JsonNode json = objectMapper.readTree(req.body());

				int userId = json.get("user_id").asInt();
				String username = json.get("username").asText();
				String email = json.get("email").asText();
				boolean emailVerified = json.get("email_verified").asBoolean();
				String createdAt = json.has("created_at") ? json.get("created_at").asText() : "";
				String lastLogin = json.has("last_login") ? json.get("last_login").asText() : "";

				UserSessionManager.UserData userData = new UserSessionManager.UserData(
					userId, username, email, emailVerified, createdAt, lastLogin
				);

				sessionManager.updateUserData(userData);

				return objectMapper.writeValueAsString(Map.of(
					"status", "success",
					"message", "User data updated"
				));

			} catch (Exception e) {
				Logger.error("Failed to update user data: " + e.getMessage());
				res.status(500);
				return objectMapper.writeValueAsString(Map.of(
					"error", "update_failed",
					"message", "Failed to update user data"
				));
			}
		});

		// Clear session (logout)
		Spark.post("/api/session/clear", (req, res) -> {
			res.type("application/json");
			try {
				sessionManager.clearSession();

				return objectMapper.writeValueAsString(Map.of(
					"status", "success",
					"message", "Session cleared successfully"
				));

			} catch (Exception e) {
				Logger.error("Failed to clear session: " + e.getMessage());
				res.status(500);
				return objectMapper.writeValueAsString(Map.of(
					"error", "clear_failed",
					"message", "Failed to clear session data"
				));
			}
		});

		// Get session statistics
		Spark.get("/api/session/stats", (req, res) -> {
			res.type("application/json");
			try {
				Optional<UserSessionManager.SessionStats> stats = sessionManager.getSessionStats();

				if (stats.isEmpty()) {
					return objectMapper.writeValueAsString(Map.of(
						"status", "no_session",
						"message", "No active session"
					));
				}

				UserSessionManager.SessionStats sessionStats = stats.get();
				return objectMapper.writeValueAsString(Map.of(
					"status", "success",
					"stats", Map.of(
						"username", sessionStats.getUsername(),
						"login_time", sessionStats.getLoginTime(),
						"last_token_refresh", sessionStats.getLastTokenRefresh(),
						"operating_system", sessionStats.getOperatingSystem(),
						"store_type", sessionStats.getStoreType(),
						"email_verified", sessionStats.isEmailVerified()
					)
				));

			} catch (Exception e) {
				Logger.error("Failed to get session stats: " + e.getMessage());
				res.status(500);
				return objectMapper.writeValueAsString(Map.of(
					"error", "stats_failed",
					"message", "Failed to get session statistics"
				));
			}
		});

		// Not found handler returns JSON
		Spark.notFound((req, res) -> {
			res.type("application/json");
			return "{\"error\":\"not_found\"}";
		});

		// Exception handler hides details from client
		Spark.exception(Exception.class, (ex, req, res) -> {
			res.type("application/json");
			res.status(500);
			res.body("{\"error\":\"internal_error\"}");
			try { Logger.error("Unhandled exception: " + ex.getMessage()); } catch (Throwable ignored) {}
		});

		try { Logger.info("Server started on port " + port + " with secure session management"); } catch (Throwable ignored) {}
	}
}
