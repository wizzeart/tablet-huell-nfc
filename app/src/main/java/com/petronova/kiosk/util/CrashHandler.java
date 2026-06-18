package com.petronova.kiosk.util;

import androidx.annotation.NonNull;

/**
 * Captura excepciones no controladas y las guarda en el log.
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CrashHandler() {
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void init() {
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler());
    }

    @Override
    public void uncaughtException(@NonNull Thread t, @NonNull Throwable e) {
        FileLogger.e("CRASH", "Excepción no controlada en hilo: " + t.getName(), e);
        
        // Dejar que el sistema maneje el cierre normal (o reiniciar si fuera necesario)
        if (defaultHandler != null) {
            defaultHandler.uncaughtException(t, e);
        }
    }
}
