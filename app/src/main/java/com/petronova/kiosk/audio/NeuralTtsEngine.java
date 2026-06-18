package com.petronova.kiosk.audio;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Motor TTS neuronal OFFLINE, embebido en la app (no usa el TTS de Google).
 *
 * Usa sherpa-onnx con un modelo VITS/Piper en español latino
 * (es_MX-claude-high, int8) incluido en assets/tts. La voz es neuronal,
 * muy natural y fluida, y funciona sin internet ni motor TTS del sistema.
 *
 * Flujo:
 *   1. init(): copia el modelo de assets → almacenamiento interno (una vez) y
 *      carga sherpa-onnx en un hilo de fondo.
 *   2. speak(): sintetiza el texto a PCM y lo reproduce por AudioTrack.
 *      Un nuevo speak() interrumpe el anterior (semántica "flush").
 *
 * Si algo falla (sin .so, modelo ausente, etc.) queda ready=false y
 * {@link TtsManager} cae al TTS nativo de Android como respaldo.
 */
public final class NeuralTtsEngine {

    private static final String TAG = "NeuralTtsEngine";

    // Carpeta y archivos del modelo dentro de assets/.
    private static final String ASSET_DIR   = "tts";
    private static final String MODEL_FILE  = "es_ES-davefx-medium.onnx";
    private static final String TOKENS_FILE = "tokens.txt";
    private static final String ESPEAK_DIR  = "espeak-ng-data";
    /** Marcador de versión: si cambia, se vuelve a copiar el modelo. */
    private static final String COPY_MARKER = ".tts_v2_es_ES_davefx";
    /** Velocidad de habla (1.0 = normal; >1 = más rápida). */
    private static final float  SPEAK_SPEED = 1.1f;

    private static NeuralTtsEngine instance;

    private final ExecutorService player = Executors.newSingleThreadExecutor();
    private final AtomicLong       seq    = new AtomicLong(0);

    private volatile OfflineTts  tts;
    private volatile AudioTrack  currentTrack;
    private volatile boolean     ready   = false;
    private volatile boolean     enabled = true;

    private NeuralTtsEngine() {}

    public static synchronized NeuralTtsEngine getInstance() {
        if (instance == null) instance = new NeuralTtsEngine();
        return instance;
    }

    public boolean isReady() { return ready; }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) stopCurrent();
    }

    /** Inicializa el motor en segundo plano (copia modelo + carga sherpa-onnx). */
    public void init(Context context) {
        if (ready || tts != null) return;
        final Context app = context.getApplicationContext();
        player.execute(() -> {
            try {
                File dir = new File(app.getFilesDir(), ASSET_DIR);
                ensureModelCopied(app, dir);

                OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
                vits.setModel(new File(dir, MODEL_FILE).getAbsolutePath());
                vits.setTokens(new File(dir, TOKENS_FILE).getAbsolutePath());
                vits.setDataDir(new File(dir, ESPEAK_DIR).getAbsolutePath());

                OfflineTtsModelConfig model = new OfflineTtsModelConfig();
                model.setVits(vits);
                // Usar varios hilos para acelerar la síntesis (reduce el lag).
                int cores = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
                model.setNumThreads(cores);
                model.setDebug(false);
                model.setProvider("cpu");

                OfflineTtsConfig config = new OfflineTtsConfig();
                config.setModel(model);

                // assetManager = null → sherpa-onnx lee desde el sistema de archivos.
                OfflineTts engine = new OfflineTts((AssetManager) null, config);

                // Pre-calentar: la PRIMERA síntesis es la más lenta (carga de grafos,
                // espeak, cachés). La hacemos aquí y la descartamos, así el primer
                // mensaje real ya sale rápido.
                try { engine.generate("hola", 0, SPEAK_SPEED); } catch (Throwable ignored) {}

                tts = engine;
                ready = true;
                Log.i(TAG, "TTS neuronal listo (es_ES-davefx-medium, "
                        + cores + " hilos, " + tts.sampleRate() + " Hz)");
            } catch (Throwable t) {
                ready = false;
                Log.e(TAG, "No se pudo iniciar el TTS neuronal: " + t.getMessage(), t);
            }
        });
    }

    /** Sintetiza y reproduce el texto. Interrumpe cualquier locución en curso. */
    public void speak(String text) {
        if (!enabled || !ready || tts == null || text == null || text.isEmpty()) return;
        final long id = seq.incrementAndGet();
        stopCurrent(); // flush: corta lo que se esté reproduciendo
        final String toSay = text;
        player.execute(() -> {
            if (id != seq.get()) return;            // superado por otro mensaje
            try {
                GeneratedAudio audio = tts.generate(toSay, 0, SPEAK_SPEED);
                if (id != seq.get()) return;        // superado mientras sintetizaba
                float[] samples = audio.getSamples();
                int sampleRate  = audio.getSampleRate();
                if (samples == null || samples.length == 0) return;
                playSamples(samples, sampleRate, id);
            } catch (Throwable t) {
                Log.e(TAG, "Error sintetizando: " + t.getMessage());
            }
        });
    }

    private void playSamples(float[] samples, int sampleRate, long id) {
        AudioTrack track = null;
        try {
            int bytes = samples.length * 4; // 4 bytes por float
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();

            track.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
            track.play();
            currentTrack = track;

            // Esperar a que termine (permite que un nuevo speak lo interrumpa).
            long durationMs = (long) (samples.length * 1000L / sampleRate) + 120L;
            long deadline = System.currentTimeMillis() + durationMs;
            while (System.currentTimeMillis() < deadline) {
                if (id != seq.get()) break;         // interrumpido
                try { Thread.sleep(40); } catch (InterruptedException e) { break; }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error reproduciendo: " + t.getMessage());
        } finally {
            releaseTrack(track);
            if (currentTrack == track) currentTrack = null;
        }
    }

    private void stopCurrent() {
        AudioTrack t = currentTrack;
        currentTrack = null;
        releaseTrack(t);
    }

    private static void releaseTrack(AudioTrack t) {
        if (t == null) return;
        try { if (t.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) { t.pause(); t.flush(); t.stop(); } }
        catch (Throwable ignored) {}
        try { t.release(); } catch (Throwable ignored) {}
    }

    // ─── Copia de assets/tts → filesDir/tts (recursiva, una sola vez) ──────────

    private void ensureModelCopied(Context ctx, File destDir) throws Exception {
        File marker = new File(destDir, COPY_MARKER);
        if (marker.exists() && new File(destDir, MODEL_FILE).exists()) {
            return; // ya copiado en esta versión
        }
        if (destDir.exists()) deleteRecursive(destDir);
        if (!destDir.mkdirs() && !destDir.isDirectory()) {
            throw new IllegalStateException("No se pudo crear " + destDir);
        }
        copyAsset(ctx.getAssets(), ASSET_DIR, destDir.getParentFile());
        // Escribir marcador al final (indica copia completa).
        try (OutputStream os = new FileOutputStream(marker)) { os.write(1); }
        Log.i(TAG, "Modelo TTS copiado a " + destDir);
    }

    /**
     * Copia recursivamente un asset (archivo o carpeta) a outParent.
     * Para "tts" crea outParent/tts con todo su contenido.
     */
    private void copyAsset(AssetManager am, String assetPath, File outParent) throws Exception {
        String[] children = am.list(assetPath);
        if (children == null || children.length == 0) {
            // Es un archivo.
            File outFile = new File(outParent, assetPath);
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream in = am.open(assetPath);
                 OutputStream out = new FileOutputStream(outFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } else {
            // Es una carpeta.
            File outDir = new File(outParent, assetPath);
            if (!outDir.exists()) outDir.mkdirs();
            for (String child : children) {
                copyAsset(am, assetPath + "/" + child, outParent);
            }
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public void shutdown() {
        stopCurrent();
        OfflineTts t = tts;
        tts = null;
        ready = false;
        if (t != null) { try { t.release(); } catch (Throwable ignored) {} }
    }
}
