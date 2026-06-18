package com.petronova.kiosk.hardware.impl;

import android.app.Activity;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import com.petronova.kiosk.util.FileLogger;
import com.petronova.kiosk.hardware.NfcController;

/**
 * Controlador NFC nativo para Android.
 * Usa NfcAdapter.enableReaderMode() (foreground dispatch alternativo, más eficiente).
 * Equivalente funcional de la lectura NFC en scanning.js:startNFCScan() → POST /scan_nfc
 * y la búsqueda en fingerprint_db.py:get_trabajador_by_nfc().
 */
public class AndroidNfcController implements NfcController {

    private static final String TAG = "NfcController";

    private final Activity    activity;
    private final NfcAdapter  nfcAdapter;
    private NfcReadCallback   callback;
    private boolean           reading = false;

    public AndroidNfcController(Activity activity) {
        this.activity   = activity;
        this.nfcAdapter = NfcAdapter.getDefaultAdapter(activity);
    }

    @Override
    public boolean isAvailable() {
        return nfcAdapter != null && nfcAdapter.isEnabled();
    }

    @Override
    public void startReading(NfcReadCallback callback) {
        if (!isAvailable()) {
            FileLogger.w(TAG, "NFC no disponible en este dispositivo");
            return;
        }
        this.callback = callback;
        this.reading  = true;

        // Reader mode: solo activa NFC sin overhead de NDEF dispatch
        Bundle options = new Bundle();
        options.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);

        nfcAdapter.enableReaderMode(
            activity,
            tag -> {
                if (!reading) return;
                String uid = bytesToHex(tag.getId());
                FileLogger.d(TAG, "Tag NFC detectado: " + uid);
                // Notificar en hilo principal
                activity.runOnUiThread(() -> {
                    if (callback != null) callback.onTagDetected(uid);
                });
            },
            NfcAdapter.FLAG_READER_NFC_A |
            NfcAdapter.FLAG_READER_NFC_B |
            NfcAdapter.FLAG_READER_NFC_F |
            NfcAdapter.FLAG_READER_NFC_V |
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            options
        );
        FileLogger.d(TAG, "Modo reader NFC activado");
    }

    @Override
    public void stopReading() {
        reading  = false;
        callback = null;
        if (nfcAdapter != null) {
            try { nfcAdapter.disableReaderMode(activity); } catch (Exception ignore) {}
        }
        FileLogger.d(TAG, "Modo reader NFC desactivado");
    }

    /** Convierte el ID del tag (bytes) a string hex (equivalente al uid en la BD). */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }
}
