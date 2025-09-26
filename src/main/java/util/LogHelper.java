package util;

import config.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;

public class LogHelper {
    private static final String LOGS_FOLDER = Config.getString("LogHelper.directory", "\\Tungsten-client");
    private static final int maxLogs = Config.getInt("LogHelper.maxLogs", 5);
    private static final long MAX_LOG_SIZE_BYTES = Config.getInt("LogHelper.maxSize", 52428800); // 50 MiB
    private static String logFile;

	/**
	 * Deletes the oldest log files if the number of logs exceeds maxLogs
	 * @param logDir The directory containing the log files
	 */
	private static void cleanOldLogs(String logDir) {
		try {
			File dir = new File(logDir);
			File[] logFiles = dir.listFiles((d, name) -> name.endsWith(".log"));

			if (logFiles != null && logFiles.length > maxLogs) {
				// Sort files by last modified date (oldest first)
				Arrays.sort(logFiles, Comparator.comparingLong(File::lastModified));

				// Delete older files until we're under the limit
				int filesToDelete = logFiles.length - maxLogs;
				for (int i = 0; i < filesToDelete; i++) {
					try {
						Files.deleteIfExists(logFiles[i].toPath());
						Logger.debug("Old log deleted: " + logFiles[i].getName());
					} catch (IOException e) {
						Logger.error("Error deleting log " + logFiles[i].getName() + ": " + e.getMessage());
					}
				}
			}
		} catch (Exception e) {
			Logger.error("Error cleaning old logs: " + e.getMessage());
		}
	}

    /**
     * Creates a new log file with a timestamp in the logs directory.
     * The file will be named in the format: log_DD-MM-YY - HH.mm.log
     */
    public static void createLog() {
        try {
            // Create logs directory if it doesn't exist
            String logDir = Config.configDir + LOGS_FOLDER;
            Files.createDirectories(Paths.get(logDir));

	        cleanOldLogs(logDir);
            
            // Generate timestamp for the filename
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd-MM-yy - HH-mm");
            String timestamp = LocalDateTime.now().format(formatter);
            
            // Set the log file path
            logFile = logDir + File.separator + timestamp + ".log";
            
            // Create the log file if it doesn't exist
            File file = new File(logFile);
            if (!file.exists()) {
                file.createNewFile();
            }
        } catch (IOException e) {
            Logger.error("Something went wrong when creating a log " + e);
	        Logger.error(Arrays.toString(e.getStackTrace()));
            throw new RuntimeException(e);
        }
    }

    /**
     * Checks if the current log file has exceeded the maximum allowed size
     * @return true if the file exists and exceeds MAX_LOG_SIZE_BYTES, false otherwise
     */
    private static boolean isLogFileTooLarge() {
        if (logFile == null) {
            return false;
        }
        File file = new File(logFile);
        return file.exists() && file.length() > MAX_LOG_SIZE_BYTES;
    }

    /**
     * Adds content to the current log file. If the current log file exceeds the maximum
     * allowed size (50 MiB), a new log file is created.
     * @param contentToAdd The content to add to the log file
     */
    public static void addToLog(String contentToAdd) {
        if (logFile == null || isLogFileTooLarge()) {
            createLog();
        }

        String content = contentToAdd + "\n";
        
        try {
            Files.writeString(
                Paths.get(logFile),
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Error when writing to the log file ", e);
        }
    }
}
