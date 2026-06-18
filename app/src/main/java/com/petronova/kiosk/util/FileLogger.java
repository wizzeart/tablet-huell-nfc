package com.petronova.kiosk.util;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Utilidad para guardar logs en archivos dentro de la carpeta /logs.
 */
public class FileLogger {
    private static final String TAG = "FileLogger";
    private static final String LOG_DIR = "logs";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
    private static final SimpleDateFormat FILE_NAME_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    private static File logDirectory;

    public static void init(Context context) {
        File baseDir = context.getExternalFilesDir(null);
        if (baseDir == null) {
            baseDir = context.getFilesDir();
        }
        logDirectory = new File(baseDir, LOG_DIR);
        if (!logDirectory.exists()) {
            boolean created = logDirectory.mkdirs();
            if (!created) {
                Log.e(TAG, "No se pudo crear la carpeta de logs: " + logDirectory.getAbsolutePath());
            }
        }
        Log.i(TAG, "Logs se guardarán en: " + logDirectory.getAbsolutePath());
    }

    public static void d(String tag, String message) {
        log("DEBUG", tag, message, null);
    }

    public static void i(String tag, String message) {
        log("INFO", tag, message, null);
    }

    public static void w(String tag, String message) {
        log("WARN", tag, message, null);
    }

    public static void e(String tag, String message) {
        log("ERROR", tag, message, null);
    }

    public static void e(String tag, String message, Throwable throwable) {
        log("ERROR", tag, message, throwable);
    }

    private static synchronized void log(String level, String tag, String message, Throwable throwable) {
        String timestamp = DATE_FORMAT.format(new Date());
        String logEntry = String.format("%s [%s] %s: %s\n", timestamp, level, tag, message);
        
        // También imprimir en Logcat
        switch (level) {
            case "DEBUG": Log.d(tag, message); break;
            case "INFO":  Log.i(tag, message); break;
            case "WARN":  Log.w(tag, message); break;
            case "ERROR": Log.e(tag, message, throwable); break;
        }

        if (logDirectory == null) return;

        String fileName = FILE_NAME_FORMAT.format(new Date()) + ".log";
        File logFile = new File(logDirectory, fileName);

        try (FileWriter fw = new FileWriter(logFile, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.print(logEntry);
            if (throwable != null) {
                throwable.printStackTrace(pw);
            }
            pw.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error escribiendo en archivo de log", e);
        }
    }
}
