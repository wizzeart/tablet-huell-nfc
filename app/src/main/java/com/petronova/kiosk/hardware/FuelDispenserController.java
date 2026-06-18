package com.petronova.kiosk.hardware;

/**
 * Interfaz del controlador del dispensador de combustible.
 * FUERA DE ALCANCE en esta fase — stub con TODO[DISPENSADOR].
 *
 * Hoy en el sistema Python (integracion.py):
 *   - GPIO pin 26: sensor de flujo (entrada, pull-up)
 *   - GPIO pin 24: relé del dispensador (salida)
 *   - Calibración: PULSOS_POR_LITRO = 17.78
 *   - Librería: lgpio (Linux GPIO)
 *
 * En Android no hay GPIO directo. Opciones para la siguiente fase:
 *   (a) Raspberry Pi auxiliar conectada por USB/Serial que ejecuta integracion.py
 *       → la APK envía litros via serial/TCP y recibe logs
 *   (b) Microcontrolador (ESP32/Arduino) con protocolo serie
 *       → la APK usa UsbSerialForAndroid para controlarlo
 *   (c) Equipo Linux en red → la APK llama un endpoint REST
 */
public interface FuelDispenserController {

    /**
     * Inicia el dispensado de combustible.
     * TODO[DISPENSADOR]: implementar según el hardware disponible.
     * @param liters   cantidad a dispensar
     * @param callback recibe logs en tiempo real + evento done
     */
    void dispense(float liters, DispenseCallback callback);

    /** Detiene el dispensado de emergencia. */
    void stop();

    interface DispenseCallback {
        /** Llamado con cada línea de log del dispensador (equivalente dispensador_log). */
        void onLog(String log);
        /** Llamado cuando el dispensado termina (equivalente dispensador_done). */
        void onDone(int exitCode);
        void onError(String message);
    }
}
