package com.petronova.kiosk.audio;

import android.media.AudioManager;
import android.media.ToneGenerator;

/**
 * Beeps del sistema del kiosko.
 * Equivalente de audio.js: playNumpadBeep(), playFingerprintBeep(), playSuccessBeep(), playErrorBeep().
 * Usa ToneGenerator — sin archivos de audio, sin red.
 */
public final class SoundManager {

    private SoundManager() {}

    /** Beep de teclado numérico (equivalente playNumpadBeep, 1500 Hz). */
    public static void playNumpadBeep() {
        beep(ToneGenerator.TONE_DTMF_0, 60);
    }

    /** Beep de captura de huella (equivalente playFingerprintBeep, 2000 Hz). */
    public static void playFingerprintBeep() {
        beep(ToneGenerator.TONE_SUP_PIP, 100);
    }

    /** Beep de éxito (equivalente playSuccessBeep). */
    public static void playSuccessBeep() {
        beep(ToneGenerator.TONE_PROP_ACK, 200);
    }

    /** Beep de error (equivalente playErrorBeep). */
    public static void playErrorBeep() {
        beep(ToneGenerator.TONE_SUP_ERROR, 300);
    }

    private static void beep(int tone, int durationMs) {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_SYSTEM, 80);
            tg.startTone(tone, durationMs);
            // ToneGenerator se libera automáticamente tras el tono
        } catch (Exception ignore) {}
    }
}
