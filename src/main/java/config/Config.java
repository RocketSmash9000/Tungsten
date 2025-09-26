package config;

import util.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Generic class to handle application configuration.
 * Provides static methods to read and write configuration properties
 * in a file located in the user's configuration directory.
 */
public class Config {
    public static String configDir;
    private static String configFile;
    private static final Properties props = new Properties();
    private static volatile boolean configuracionCargada = false;
    private static final Object lock = new Object();

    // Lazy initialization
    private static void asegurarConfiguracionCargada() {
        if (!configuracionCargada) {
            synchronized (lock) {
                if (!configuracionCargada) {
                    cargarConfiguracion();
                    configuracionCargada = true;
                }
            }
        }
    }

    /**
     * Loads the configuration from the file, or creates an empty one if it doesn't exist.
     */
    private static void cargarConfiguracion() {
        configFile = configDir + "\\user.cfg";
		File configFile = new File(Config.configFile);

        try {
            // Create directory if it doesn't exist
            Files.createDirectories(Paths.get(configDir));

            if (configFile.exists()) {
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    props.load(fis);
                    //Logger.debug("Configuración cargada correctamente desde " + CONFIG_FILE);
                }
            } else {
                // Save empty configuration file
                guardarConfiguracion();
                //Logger.debug("Archivo de configuración creado en " + CONFIG_FILE);
            }
        } catch (IOException e) {
            Logger.error("Error al cargar la configuración: " + e);
        }
    }

    /**
     * Saves the current configuration to the file.
     */
    private static void guardarConfiguracion() {
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            props.store(fos, "Configuración de la aplicación");
            Logger.debug("Configuración guardada correctamente en " + configFile);
        } catch (IOException e) {
            Logger.error("Error al guardar la configuración: " + e);
        }
    }

    /**
     * Gets a configuration value as a String.
     * If the key doesn't exist, saves the default value to the configuration.
     * 
     * @param key The property key
     * @param defaultValue Default value if the property doesn't exist
     * @return The property value or the default value
     */
    public static String getString(String key, String defaultValue) {
        asegurarConfiguracionCargada();
        
        // Si la clave no existe, guardar el valor por defecto
        if (!props.containsKey(key) && defaultValue != null) {
            props.setProperty(key, defaultValue);
            // Use a new thread to save and avoid blocking
            new Thread(Config::guardarConfiguracion, "Config-Save-Thread").start();
        }
        
        return props.getProperty(key, defaultValue);
    }

    /**
     * Gets a configuration value as an integer.
     * If the key doesn't exist, saves the default value to the configuration.
     * 
     * @param key The property key
     * @param defaultValue Default value if the property doesn't exist or is not a valid integer
     * @return The property value as an integer or the default value
     */
    public static int getInt(String key, int defaultValue) {
        asegurarConfiguracionCargada();
        String stringValue = props.getProperty(key);
        
        // If the key does not exist, save the default value
        if (stringValue == null) {
            setInt(key, defaultValue);
            return defaultValue;
        }
        
        // If the key exists but the value is not a number, try to convert it
        try {
            return Integer.parseInt(stringValue);
        } catch (NumberFormatException e) {
            Logger.error(String.format("Invalid value for property '%s': %s. Using default value: %d", 
                key, stringValue, defaultValue));
            // Fix the invalid value with the default value
            setInt(key, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Sets a configuration value.
     *
     * @param key The property key
     * @param value The value to set
     */
    public static void set(String key, String value) {
        asegurarConfiguracionCargada();
        String oldValue = props.getProperty(key);
        if (value == null) {
            props.remove(key);
        } else {
            props.setProperty(key, value);
        }
        // Only save if the value has changed
        if (oldValue == null || !oldValue.equals(value)) {
            guardarConfiguracion();
        }
    }

    /**
     * Sets an integer configuration value.
     *
     * @param key The property key
     * @param value The integer value to set
     */
    public static void setInt(String key, int value) {
        set(key, String.valueOf(value));
    }

    /**
     * Removes a configuration property.
     *
     * @param key The key of the property to remove
     */
    public static void remove(String key) {
        asegurarConfiguracionCargada();
        if (props.containsKey(key)) {
            props.remove(key);
            guardarConfiguracion();
        }
    }

    /**
     * Clears all configuration.
     */
    public static void clear() {
        asegurarConfiguracionCargada();
        if (!props.isEmpty()) {
            props.clear();
            guardarConfiguracion();
        }
    }
}
