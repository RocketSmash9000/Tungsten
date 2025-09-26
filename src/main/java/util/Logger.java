package util;

import config.Config;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static org.fusesource.jansi.Ansi.ansi;

public class Logger {
	/**
	 * {@code logLevel} shows the level of information shown in the console. By default, {@code logLevel} is 1.
	 * Only higher or equal levels will be printed. All levels will be added to the log file managed by the {@code LogHelper} class.
	 *
	 * <h3>List of all log levels</h3>
	 * <ul>
	 * <li>0 - DEBUG
	 * <li>1 - INFO
	 * <li>2 - WARN
	 * <li>3 - ERROR
	 * <li>4 - FATAL -> Always shown
	 * </ul>
	 */
	private static final int logLevel = Config.getInt("Logger.logLevel", 1);

	/**
	 * Prints at level 0 (DEBUG) to the console. Will only print to console if {@code logLevel} = 0.
	 * @param string the character sequence to print
	 */
	public static void debug(String string) {
		LocalDate fecha = LocalDate.now();
		LocalTime hora = LocalTime.now();
		DateTimeFormatter formatHour = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
		DateTimeFormatter formatDate = DateTimeFormatter.ofPattern("dd/MM/yy");

		String output = "DEBUG: [" + fecha.format(formatDate) + " - " + hora.format(formatHour) + "] : " + string;

		if (logLevel == 0) {
			System.out.println(ansi().fgBrightGreen().a(output).reset());
		}
		LogHelper.addToLog(output);
	}

	/**
	 * Prints at level 1 (INFO) to the console. The minimum level to print to console (according to {@code logLevel}) is 1
	 * @param string the character sequence to print
	 */
	public static void info(String string) {
		LocalDate fecha = LocalDate.now();
		LocalTime hora = LocalTime.now();
		DateTimeFormatter formatHour = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
		DateTimeFormatter formatDate = DateTimeFormatter.ofPattern("dd/MM/yy");

		String output = "INFO:  [" + fecha.format(formatDate) + " - " + hora.format(formatHour) + "] : " + string;

		if (logLevel <= 1) {
			System.out.println(ansi().fgBrightBlue().a(output).reset());
		}
		LogHelper.addToLog(output);
	}

	/**
	 * Prints at level 2 (WARN) to the console.
	 * @param string the character sequence to print
	 */
	public static void warn(String string) {
		LocalDate fecha = LocalDate.now();
		LocalTime hora = LocalTime.now();
		DateTimeFormatter formatHour = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
		DateTimeFormatter formatDate = DateTimeFormatter.ofPattern("dd/MM/yy");

		String output = "WARN:  [" + fecha.format(formatDate) + " - " + hora.format(formatHour) + "] : " + string;

		if (logLevel <= 2) {
			System.out.println(ansi().fgRgb(255, 170, 0).a(output).reset());
		}
		LogHelper.addToLog(output);
	}

	/**
	 * Prints at level 3 (ERROR) to the console
	 * @param string the character sequence to print
	 */
	public static void error(String string) {
		LocalDate fecha = LocalDate.now();
		LocalTime hora = LocalTime.now();
		DateTimeFormatter formatHour = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
		DateTimeFormatter formatDate = DateTimeFormatter.ofPattern("dd/MM/yy");

		String output = "ERROR: [" + fecha.format(formatDate) + " - " + hora.format(formatHour) + "] : " + string;

		if (logLevel <= 3) {
			System.out.println(ansi().fgRgb(255, 170, 0).a(output).reset());
		}
		LogHelper.addToLog(output);
	}

	/**
	 * Prints at level 4 (FATAL) to the console. Level 4 is always printed in all cases
	 * @param string the character sequence to print
	 */
	public static void fatal(String string) {
		LocalDate fecha = LocalDate.now();
		LocalTime hora = LocalTime.now();
		DateTimeFormatter formatHour = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
		DateTimeFormatter formatDate = DateTimeFormatter.ofPattern("dd/MM/yy");

		String output = "FATAL: [" + fecha.format(formatDate) + " - " + hora.format(formatHour) + "] : " + string;
		System.out.println(ansi().fgRgb(255, 0, 0).a(output).reset());
		LogHelper.addToLog(output);
	}
}
