package com.petronova.kiosk.hardware.impl;

import android.content.Context;

import com.petronova.kiosk.KioskApplication;
import com.petronova.kiosk.hardware.FingerprintController;
import com.petronova.kiosk.util.FileLogger;

/**
 * Fábrica del controlador de huella activo. Único punto de inyección del lector.
 *
 * Estrategia: auto-detección con fallback.
 *   1. Intenta inicializar el lector MX (placa ZY) vía {@link MxFingerprintController}.
 *   2. Si la inicialización falla (sin hardware, sin .so, USB no conectado), cae al
 *      {@link PlaceholderFingerprintController} para que la app siga funcionando sin crashear.
 */
public final class FingerprintControllerProvider {

    private static final String TAG = "FingerprintCtrlProv";

    private FingerprintControllerProvider() {}

    /** Devuelve la implementación activa, probando primero el lector MX real. */
    public static FingerprintController create() {
        Context ctx = appContext();
        if (ctx != null) {
            try {
                MxFingerprintController mx = new MxFingerprintController(ctx);
                if (mx.initDevice()) {
                    FileLogger.i(TAG, "Lector de huella MX activo");
                    return mx;
                }
                FileLogger.w(TAG, "Lector MX no disponible — usando stub (sin hardware)");
            } catch (Throwable t) {
                FileLogger.e(TAG, "Error creando lector MX, usando stub: " + t.getMessage());
            }
        } else {
            FileLogger.w(TAG, "Sin contexto de aplicación — usando stub");
        }
        return new PlaceholderFingerprintController();
    }

    private static Context appContext() {
        try {
            KioskApplication app = KioskApplication.getInstance();
            return app != null ? app.getApplicationContext() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
