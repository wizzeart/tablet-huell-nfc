package com.petronova.kiosk.data.db;

import com.petronova.kiosk.config.AppConfig;
import com.petronova.kiosk.util.FileLogger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Equivalente de get_db_connection() en fingerprint_db.py.
 * Crea conexiones JDBC a PostgreSQL. No usa pool (igual que el código Python actual —
 * cada función abre y cierra su propia conexión).
 *
 * NOTA: todas las llamadas a getConnection() deben realizarse en un hilo de fondo
 * (ExecutorService / AsyncTask), nunca en el hilo de UI.
 */
public final class DbConnectionFactory {
    private static final String TAG = "DB_CONN";

    private static final String JDBC_URL = String.format(
            "jdbc:postgresql://%s:%d/%s",
            AppConfig.DB_HOST, AppConfig.DB_PORT, AppConfig.DB_NAME
    );

    private DbConnectionFactory() {}

    /** Llama a Class.forName para registrar el driver antes de usarlo. */
    public static void preloadDriver() {
        try {
            Class.forName("org.postgresql.Driver");
            FileLogger.i(TAG, "Driver PostgreSQL cargado: " + JDBC_URL);
        } catch (ClassNotFoundException e) {
            FileLogger.e(TAG, "Driver PostgreSQL no encontrado", e);
            throw new RuntimeException("Driver PostgreSQL no encontrado en classpath", e);
        }
    }

    /**
     * Abre y devuelve una nueva conexión a PostgreSQL.
     * El llamador es responsable de cerrarla (try-with-resources recomendado).
     */
    public static Connection getConnection() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user",     AppConfig.DB_USER);
        props.setProperty("password", AppConfig.DB_PASSWORD);
        props.setProperty("ssl",      "false");
        props.setProperty("connectTimeout", "10");
        props.setProperty("socketTimeout",  "30");
        // Desactiva explícitamente el límite de maxResultBuffer (sin buffering por %).
        // NOTA: la incompatibilidad real con Android (ManagementFactory) está resuelta
        // reemplazando la clase org.postgresql.util.PGPropertyMaxResultBufferParser
        // (ver app/src/main/java/org/postgresql/util/ y el jar parcheado en app/libs).
        props.setProperty("maxResultBuffer", "-1");
        try {
            return DriverManager.getConnection(JDBC_URL, props);
        } catch (SQLException e) {
            FileLogger.e(TAG, "Error conectando a " + JDBC_URL + " (user: " + AppConfig.DB_USER + ")", e);
            throw e;
        } catch (Throwable t) {
            // Red de seguridad: NoClassDefFoundError u otros Error del driver JDBC
            // que no son SQLException. Convertir a SQLException para que el caller
            // los maneje sin crashear la app.
            FileLogger.e(TAG, "Error inesperado del driver JDBC al conectar: " + t.getMessage());
            throw new SQLException("Error del driver JDBC: " + t.getMessage(), t);
        }
    }
}
