package com.petronova.kiosk.hardware;

/** Interfaz del controlador NFC. Implementada por AndroidNfcController. */
public interface NfcController {
    /** Inicia la escucha de tags NFC en foreground (reader mode). */
    void startReading(NfcReadCallback callback);
    /** Detiene la escucha de tags NFC. */
    void stopReading();
    /** True si el dispositivo tiene NFC y está habilitado. */
    boolean isAvailable();

    interface NfcReadCallback {
        /** Llamado en el hilo principal al detectar un tag. */
        void onTagDetected(String uid);
    }
}
