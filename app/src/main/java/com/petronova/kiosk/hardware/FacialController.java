package com.petronova.kiosk.hardware;

/**
 * Interfaz del controlador de reconocimiento facial.
 * FUERA DE ALCANCE en esta fase — stub con TODO[FACIAL].
 * Hoy en el sistema Python lo maneja una cámara remota vía POST /register_verify + WebSocket.
 */
public interface FacialController {

    /**
     * Inicia la captura facial para el trabajador dado.
     * TODO[FACIAL]: implementar con CameraX + modelo de reconocimiento (TFLite, MLKit, etc.)
     *   o integrar con cámara remota vía REST/WebSocket.
     */
    void startCapture(int expectedWorkerId, FacialCallback callback);

    /** Cancela la captura en curso. */
    void cancel();

    interface FacialCallback {
        void onSuccess(int recognizedWorkerId);
        void onError(String message);
    }
}
