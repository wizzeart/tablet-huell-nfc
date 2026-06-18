package com.petronova.kiosk.sensors;

import com.petronova.kiosk.util.FileLogger;

/**
 * Coordinador de sensores con exclusión mutua.
 * Equivalente de los endpoints /sensor/* en api.py y sensors.js en el frontend.
 *
 * Garantiza que solo un sensor esté activo a la vez (huella o NFC),
 * y permite pausar/reanudar el loop de detección automática.
 *
 * Reemplaza:
 *   - POST /sensor/activate_fingerprint  (api.py)
 *   - POST /sensor/activate_nfc
 *   - POST /sensor/deactivate
 *   - POST /sensor/pause_loop
 *   - POST /sensor/resume_loop
 *   - GET  /sensor/status
 *   - activateSensor(), deactivateAllSensors(), getSensorStatus() en sensors.js
 */
public class SensorCoordinator {

    private static final String TAG = "SensorCoordinator";

    public enum ActiveSensor { NONE, FINGERPRINT, NFC }

    private static SensorCoordinator instance;

    private volatile ActiveSensor activeSensor = ActiveSensor.NONE;
    private volatile boolean      loopPaused   = false;

    private SensorCoordinator() {}

    public static synchronized SensorCoordinator getInstance() {
        if (instance == null) instance = new SensorCoordinator();
        return instance;
    }

    /** Activa el sensor de huella y desactiva NFC. */
    public synchronized boolean activateFingerprint() {
        if (loopPaused) {
            FileLogger.w(TAG, "Loop en pausa — no se puede activar sensor");
            return false;
        }
        activeSensor = ActiveSensor.FINGERPRINT;
        FileLogger.d(TAG, "Sensor activo: FINGERPRINT");
        return true;
    }

    /** Activa el sensor NFC y desactiva huella. */
    public synchronized boolean activateNfc() {
        if (loopPaused) {
            FileLogger.w(TAG, "Loop en pausa — no se puede activar sensor");
            return false;
        }
        activeSensor = ActiveSensor.NFC;
        FileLogger.d(TAG, "Sensor activo: NFC");
        return true;
    }

    /** Desactiva todos los sensores. */
    public synchronized void deactivateAll() {
        activeSensor = ActiveSensor.NONE;
        FileLogger.d(TAG, "Todos los sensores desactivados");
    }

    /** Pausa el loop de sensores (usado durante registro biométrico). */
    public synchronized void pauseLoop() {
        loopPaused = true;
        activeSensor = ActiveSensor.NONE;
        FileLogger.d(TAG, "Loop de sensores pausado");
    }

    /** Reanuda el loop de sensores. */
    public synchronized void resumeLoop() {
        loopPaused = false;
        FileLogger.d(TAG, "Loop de sensores reanudado");
    }

    public ActiveSensor getActiveSensor() { return activeSensor; }
    public boolean      isLoopPaused()    { return loopPaused; }
}
