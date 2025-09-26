import config.Config;

import static config.Config.configDir;

public class WebServer {
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
			java.nio.file.Files.createDirectories(java.nio.file.Paths.get(configDir));
		} catch (Exception ignored) {}

		// Start web server
		startServer();
	}

	private static void startServer() {
		// Configure port
		int port = Config.getInt("Server.port", 8080);
		spark.Spark.port(port);

  		// Static files served from src/main/resources/public
		spark.Spark.staticFiles.location("/public");
		spark.Spark.staticFiles.expireTime(600); // 10 minutes

		// Serve icons from classpath /icons at /icons/*
		spark.Spark.get("/icons/*", (req, res) -> {
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
		spark.Spark.before((req, res) -> {
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
		spark.Spark.options("/*", (req, res) -> {
			String reqHeaders = req.headers("Access-Control-Request-Headers");
			String reqMethod = req.headers("Access-Control-Request-Method");
			if (reqHeaders != null) res.header("Access-Control-Allow-Headers", reqHeaders);
			if (reqMethod != null) res.header("Access-Control-Allow-Method", reqMethod);
			return "";
		});

		// Health check
		spark.Spark.get("/health", (req, res) -> {
			res.type("application/json");
			var map = new java.util.LinkedHashMap<String, Object>();
			map.put("status", "ok");
			map.put("time", java.time.ZonedDateTime.now().toString());
			return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
		});

		// Not found handler returns JSON
		spark.Spark.notFound((req, res) -> {
			res.type("application/json");
			return "{\"error\":\"not_found\"}";
		});

		// Exception handler hides details from client
		spark.Spark.exception(Exception.class, (ex, req, res) -> {
			res.type("application/json");
			res.status(500);
			res.body("{\"error\":\"internal_error\"}");
			try { util.Logger.error("Unhandled exception: " + ex.getMessage()); } catch (Throwable ignored) {}
		});

		try { util.Logger.info("Server started on port " + port); } catch (Throwable ignored) {}
	}
}
