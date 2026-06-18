package com.petronova.kiosk.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.Locale;
import java.util.Set;

/**
 * Motor Text-to-Speech nativo de Android, configurado para la mejor voz
 * disponible en español (neuronal de Google si existe), 100% offline.
 *
 * Equivalente de tts_service.py (TTSEngine.speak()) y speakServerSide() /
 * speakWelcomeMessage() en audio.js.
 *
 * Características:
 *   - Selecciona automáticamente la voz española de mayor calidad instalada
 *     en el dispositivo (ranking por calidad + región latina + voz neuronal).
 *   - Sanea el texto antes de hablar (elimina íconos como ✓ ✗ ● … que el TTS
 *     leería de forma incorrecta) para una locución en español perfecto.
 *   - Anti-duplicado: ignora el mismo texto repetido en una ventana corta,
 *     evitando doble voz cuando un toast y un modal comparten el mismo mensaje.
 *
 * Toda la app narra sus mensajes/toasts a través de este único punto
 * (ver ToastSpeaker, StatusDialog y los helpers de estado de los fragments).
 */
public class TtsManager {

    private static final String TAG = "TtsManager";
    private static final String GOOGLE_TTS_ENGINE = "com.google.android.tts";

    /** Ventana anti-duplicado: mismo texto dentro de este lapso no se repite. */
    private static final long DEDUPE_WINDOW_MS = 1500L;

    private static TtsManager instance;

    private TextToSpeech tts;
    private boolean      ready   = false;
    private boolean      enabled = true;

    private String lastText = null;
    private long   lastTime = 0L;

    private TtsManager() {}

    public static synchronized TtsManager getInstance() {
        if (instance == null) instance = new TtsManager();
        return instance;
    }

    public void init(Context ctx) {
        Context app = ctx.getApplicationContext();
        // Motor principal: TTS neuronal offline embebido (sherpa-onnx, voz es-MX).
        NeuralTtsEngine.getInstance().init(app);
        // Respaldo: TTS nativo de Android (por si el neuronal no carga).
        if (tts == null) initEngine(app, GOOGLE_TTS_ENGINE);
    }

    private void initEngine(Context ctx, String engine) {
        tts = new TextToSpeech(ctx, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // Idioma: latino neutro primero (es-US), luego es-ES como respaldo.
                int result = tts.setLanguage(new Locale("es", "US"));
                ready = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED;

                if (!ready) {
                    Log.w(TAG, "TTS: idioma es-US no soportado, intentando es-ES");
                    result = tts.setLanguage(new Locale("es", "ES"));
                    ready = result != TextToSpeech.LANG_MISSING_DATA &&
                            result != TextToSpeech.LANG_NOT_SUPPORTED;
                }

                if (ready) {
                    // Velocidad/tono naturales para una locución clara.
                    tts.setSpeechRate(1.0f);
                    tts.setPitch(1.0f);

                    // Enrutar el audio como asistente de voz (volumen/medios correcto).
                    try {
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build());
                    } catch (Throwable ignored) {}

                    // Elegir la mejor voz española instalada.
                    selectBestSpanishVoice();

                    Log.i(TAG, "TTS inicializado con motor " + (engine != null ? engine : "default"));
                }
            } else {
                Log.e(TAG, "TTS init fallida: status=" + status + ", motor=" + engine);
                if (GOOGLE_TTS_ENGINE.equals(engine)) {
                    if (tts != null) {
                        try { tts.shutdown(); } catch (Throwable ignored) {}
                        tts = null;
                    }
                    initEngine(ctx, null); // reintentar con el motor por defecto
                }
            }
        }, engine);
    }

    /**
     * Recorre las voces disponibles y fija la española de mayor puntuación.
     * Prioriza: calidad del motor, región latina, voces neuronales (Google
     * "x-sfb"/"x-eee"/"wavenet"/"neural") y, a igualdad, voces locales (offline).
     */
    private void selectBestSpanishVoice() {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null || voices.isEmpty()) return;

            Voice best = null;
            int bestScore = Integer.MIN_VALUE;
            for (Voice v : voices) {
                if (v == null || v.getLocale() == null) continue;
                String lang = v.getLocale().getLanguage();
                String iso3 = safeIso3(v.getLocale());
                if (!"es".equalsIgnoreCase(lang) && !"spa".equalsIgnoreCase(iso3)) continue;
                // Saltar voces no instaladas.
                if (v.getFeatures() != null &&
                        v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) {
                    continue;
                }
                int score = scoreVoice(v);
                if (score > bestScore) {
                    bestScore = score;
                    best = v;
                }
            }

            if (best != null) {
                tts.setVoice(best);
                Log.i(TAG, "Voz TTS seleccionada: " + best.getName()
                        + " (" + best.getLocale()
                        + ", calidad=" + best.getQuality()
                        + ", red=" + best.isNetworkConnectionRequired() + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "No se pudo seleccionar la mejor voz: " + t.getMessage());
        }
    }

    private static int scoreVoice(Voice v) {
        int score = 0;
        // Calidad base del motor (VERY_HIGH=500 ... VERY_LOW=100).
        score += v.getQuality() * 10;

        // Región: latino neutro preferido.
        String country = v.getLocale().getCountry();
        if ("US".equalsIgnoreCase(country))       score += 60;
        else if ("MX".equalsIgnoreCase(country))  score += 55;
        else if ("419".equals(country))           score += 50;
        else if ("ES".equalsIgnoreCase(country))  score += 40;

        // Voces neuronales de Google suelen sonar mucho mejor.
        String name = v.getName() == null ? "" : v.getName().toLowerCase(Locale.ROOT);
        if (name.contains("x-sfb") || name.contains("x-eee")
                || name.contains("wavenet") || name.contains("neural")) {
            score += 30;
        }

        // A igualdad, preferir voz local (offline) por fiabilidad del kiosko.
        if (!v.isNetworkConnectionRequired()) score += 15;

        return score;
    }

    private static String safeIso3(Locale l) {
        try { return l.getISO3Language(); } catch (Throwable t) { return ""; }
    }

    /** Reproduce el texto por el altavoz (saneado y sin duplicados inmediatos). */
    public void speak(String text) {
        if (!enabled || text == null) return;

        String clean = sanitize(text);
        if (clean.isEmpty()) return;

        long now = SystemClock.elapsedRealtime();
        if (clean.equals(lastText) && (now - lastTime) < DEDUPE_WINDOW_MS) {
            return; // mismo mensaje repetido: no narrar dos veces
        }
        lastText = clean;
        lastTime = now;

        // Preferir el motor neuronal (voz natural en español); si no está listo,
        // usar el TTS nativo de Android como respaldo.
        NeuralTtsEngine neural = NeuralTtsEngine.getInstance();
        if (neural.isReady()) {
            neural.speak(clean);
        } else if (ready && tts != null) {
            tts.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "kiosk_tts_" + now);
        }
    }

    /** Reproduce un mensaje de bienvenida. Equivalente: speakWelcomeMessage() en audio.js. */
    public void speakWelcome(String workerName) {
        speak("Bienvenido, " + workerName);
    }

    /**
     * Elimina íconos/símbolos que el TTS leería mal (✓ ✗ ● • → … etc.) y
     * normaliza espacios, para una locución en español limpia.
     */
    private static String sanitize(String s) {
        if (s == null) return "";
        String out = s
                // Símbolos de estado y flechas/viñetas frecuentes en la UI.
                .replaceAll("[\\u2190-\\u21FF\\u2200-\\u22FF\\u2300-\\u27BF"
                        + "\\u2022\\u25CF\\u25A0-\\u25FF\\u2713\\u2714\\u2717\\u2718"
                        + "\\u2026\\u2B00-\\u2BFF]", " ")
                // Emojis (planos suplementarios).
                .replaceAll("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+", " ");
        out = out.replaceAll("\\s+", " ").trim();
        return out;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        NeuralTtsEngine.getInstance().setEnabled(enabled);
        if (!enabled && tts != null) tts.stop();
    }

    public boolean isEnabled() { return enabled; }

    public void shutdown() {
        NeuralTtsEngine.getInstance().shutdown();
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        ready = false;
    }
}
