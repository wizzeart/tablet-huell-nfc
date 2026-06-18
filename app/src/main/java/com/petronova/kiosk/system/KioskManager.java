package com.petronova.kiosk.system;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

/**
 * Gestiona el modo kiosko de Android (Lock Task Mode).
 * Equivalente de kiosk.js: enterKioskMode(), blockExitKeys(), preventContextMenu().
 *
 * Para que Lock Task Mode funcione, la app debe estar en la DeviceOwner policy
 * o en la lista blanca del DPC. En un Android normal solo activa Screen Pinning.
 *
 * CONFIGURACIÓN REQUERIDA:
 *   adb shell dpm set-device-owner com.petronova.kiosk/.system.KioskDeviceAdminReceiver
 *   (o configurar el MDM para whitelist la APK)
 */
public class KioskManager {

    private static final String TAG = "KioskManager";

    private final Activity activity;

    public KioskManager(Activity activity) {
        this.activity = activity;
    }

    /** Inicia Lock Task Mode si está disponible. */
    public void startKioskMode() {
        try {
            activity.startLockTask();
            Log.i(TAG, "Lock Task Mode activado");
        } catch (Exception e) {
            Log.w(TAG, "Lock Task Mode no disponible: " + e.getMessage() +
                " (requiere DeviceOwner o MDM whitelist)");
        }
    }

    /** Detiene Lock Task Mode (solo admin puede llamar esto). */
    public void stopKioskMode() {
        try {
            activity.stopLockTask();
        } catch (Exception e) {
            Log.w(TAG, "stopLockTask: " + e.getMessage());
        }
    }

    /** Llama en onResume() de la Activity para mantener el modo. */
    public void onResume() {
        ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null && am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
            startKioskMode();
        }
    }

    /** Llama en onPause() si es necesario (normalmente no hace nada). */
    public void onPause() {}
}
