package com.petronova.kiosk.service;

import android.util.Base64;
import com.petronova.kiosk.util.FileLogger;
import com.petronova.kiosk.config.AppConfig;
import com.petronova.kiosk.data.db.WorkerDao;
import com.petronova.kiosk.data.model.Worker;
import com.petronova.kiosk.hardware.FingerprintController;
import com.petronova.kiosk.hardware.MatchResult;
import com.petronova.kiosk.hardware.RawImage;
import com.petronova.kiosk.hardware.impl.FingerprintControllerProvider;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Servicio de huella dactilar — lógica de scan/enroll/matching.
 *
 * Porta 1:1 SecuGenService (secugen_service.py).
 * NO conoce qué SDK de huella se usa — solo llama a FingerprintController.
 * Al cambiar el lector, este servicio NO se modifica.
 *
 * Equivalentes Python directos:
 *   scan_fingerprint()  → secugen_service.py:108
 *   enroll_fingerprint() → secugen_service.py:201
 *   cancel()            → secugen_service.py:82
 *
 * NOTA: todos los métodos públicos son bloqueantes. Llamar desde ExecutorService,
 * nunca desde el hilo de UI.
 */
public class FingerprintService {

    private static final String TAG = "FingerprintService";

    private static FingerprintService instance;

    private final FingerprintController sensor;
    private final ReentrantLock         lock    = new ReentrantLock();
    private final AtomicBoolean         cancel  = new AtomicBoolean(false);
    private       boolean               running = false;
    private       List<Worker>          workersCache = null;

    private FingerprintService() {
        this.sensor = FingerprintControllerProvider.create();
    }

    public static synchronized FingerprintService getInstance() {
        if (instance == null) instance = new FingerprintService();
        return instance;
    }

    // ─── Ciclo de vida ───────────────────────────────────────────────────────

    /** Inicializa el sensor y carga la cache de workers. Llamar al arrancar la app. */
    public synchronized void start() {
        if (sensor.initDevice()) {
            FileLogger.i(TAG, "Sensor inicializado correctamente");
        } else {
            FileLogger.w(TAG, "No se pudo inicializar el sensor (stub o sin hardware)");
        }
        try {
            workersCache = WorkerDao.getWorkersWithHuella();
            FileLogger.i(TAG, (workersCache != null ? workersCache.size() : 0) + " trabajadores cargados de BD");
        } catch (SQLException e) {
            FileLogger.e(TAG, "Error cargando workers de BD: " + e.getMessage());
            workersCache = new java.util.ArrayList<>();
        }
        running = true;
    }

    /** Detiene el sensor y libera recursos. */
    public synchronized void stop() {
        running = false;
        sensor.setLed(false);
        sensor.closeDevice();
        sensor.terminate();
        FileLogger.i(TAG, "Servicio de huella detenido");
    }

    /** Cancela el escaneo en curso. Equivalente: SecuGenService.cancel_scan(). */
    public void cancel() {
        cancel.set(true);
    }

    // ─── Escaneo (verificación) ───────────────────────────────────────────────

    /**
     * Captura una huella y busca el trabajador correspondiente en la BD por matching iterativo.
     * Equivalente: SecuGenService.scan_fingerprint() → secugen_service.py:108
     *
     * @param timeoutSeconds timeout de captura del sensor
     * @return ScanResult con el trabajador encontrado (success=true) o con error (success=false)
     */
    public ScanResult scanFingerprint(int timeoutSeconds) {
        if (!running) {
            return ScanResult.error("Servicio no iniciado");
        }
        if (!lock.tryLock()) {
            return ScanResult.error("Sensor ocupado");
        }
        cancel.set(false);
        try {
            FileLogger.i(TAG, "=== ESCANEO DE HUELLA ===");

            if (!sensor.isDeviceOpen()) {
                sensor.initDevice();
            }
            sensor.setLed(true);

            // 1. Capturar imagen
            FileLogger.d(TAG, "Capturando imagen...");
            RawImage raw = sensor.captureImage(timeoutSeconds);
            if (raw == null) {
                return ScanResult.error("No se pudo capturar imagen (timeout o sin hardware)");
            }

            // 2. Preview para UI
            String previewBase64 = "";
            try {
                previewBase64 = sensor.rawToPreviewBase64(raw);
            } catch (Exception e) {
                FileLogger.w(TAG, "No se pudo generar preview: " + e.getMessage());
            }

            // 3. Crear template
            FileLogger.d(TAG, "Creando template...");
            byte[] template = sensor.createTemplate(raw);
            if (template == null) {
                return ScanResult.error("No se pudo crear template", previewBase64);
            }

            // 4. Cargar workers si la cache está vacía
            if (workersCache == null || workersCache.isEmpty()) {
                try {
                    workersCache = WorkerDao.getWorkersWithHuella();
                } catch (SQLException e) {
                    return ScanResult.error("Error accediendo a BD: " + e.getMessage(), previewBase64);
                }
            }

            FileLogger.d(TAG, "Comparando con " + workersCache.size() + " trabajadores...");

            // 5. Matching iterativo (equivalente al bucle en secugen_service.py:154)
            Worker bestMatch = null;
            int    bestScore = -1;
            int    skippedInvalidTemplateSize = 0;

            for (Worker worker : workersCache) {
                if (cancel.get()) {
                    return ScanResult.error("Escaneo cancelado", previewBase64);
                }
                if (worker.huellaHex == null || worker.huellaHex.isEmpty()) continue;

                byte[] stored = hexToBytes(worker.huellaHex);
                if (stored == null) continue;
                if (stored.length < 16) { // descarta templates corruptos o vacíos (SourceAFIS > 1 KB)
                    skippedInvalidTemplateSize++;
                    continue;
                }

                MatchResult mr = sensor.matchTemplates(template, stored, AppConfig.SECURITY_LEVEL);
                // Log.v(TAG, worker.nombre + " (ID " + worker.id + "): matched=" + mr.matched + ", score=" + mr.score);

                if (mr.matched && mr.score > bestScore) {
                    bestScore = mr.score;
                    bestMatch = worker;
                }
            }
            if (skippedInvalidTemplateSize > 0) {
                FileLogger.d(TAG, "Templates omitidos por tamaño incompatible: "
                        + skippedInvalidTemplateSize + " (demasiado pequeños — probablemente templates del lector anterior)");
            }

            if (bestMatch != null && bestScore >= AppConfig.MIN_CONFIDENCE) {
                FileLogger.i(TAG, "MATCH: " + bestMatch.nombre + " CI:" + bestMatch.carnet + " score:" + bestScore);
                return ScanResult.success(bestMatch, bestScore, previewBase64);
            }

            FileLogger.i(TAG, "Huella no reconocida");
            return ScanResult.error("Huella no reconocida", previewBase64);

        } catch (Exception e) {
            FileLogger.e(TAG, "Error inesperado en scanFingerprint", e);
            return ScanResult.error(e.getMessage());
        } finally {
            sensor.setLed(false);
            lock.unlock();
        }
    }

    // ─── Registro (enrolamiento) ──────────────────────────────────────────────

    /**
     * Captura una huella y la registra en BD para el trabajador dado.
     * Equivalente: SecuGenService.enroll_fingerprint() → secugen_service.py:201
     *
     * @param workerId        ID del trabajador
     * @param timeoutSeconds  timeout de captura
     * @return EnrollResult con éxito o mensaje de error
     */
    public EnrollResult enrollFingerprint(int workerId, int timeoutSeconds) {
        if (!running) return EnrollResult.error("Servicio no iniciado");

        cancel.set(false);
        FileLogger.i(TAG, "=== REGISTRO DE HUELLA para ID:" + workerId + " ===");

        if (!sensor.isDeviceOpen()) sensor.initDevice();
        sensor.setLed(true);

        try {
            // 1. Capturar
            FileLogger.d(TAG, "Capture su huella...");
            RawImage raw = sensor.captureImage(timeoutSeconds);
            if (raw == null) {
                return EnrollResult.error("No se pudo capturar imagen");
            }

            // 1b. Preview para la UI (igual que en scanFingerprint)
            String previewBase64 = "";
            try {
                previewBase64 = sensor.rawToPreviewBase64(raw);
            } catch (Exception e) {
                FileLogger.w(TAG, "No se pudo generar preview de registro: " + e.getMessage());
            }

            // 2. Template
            byte[] template = sensor.createTemplate(raw);
            if (template == null) {
                return EnrollResult.error("No se pudo crear template", previewBase64);
            }

            // 3. Convertir a HEX (equivalente a template_bytes.hex() en Python)
            String huellaHex = bytesToHex(template);
            FileLogger.d(TAG, "Template: " + template.length + " bytes → " + huellaHex.length() + " chars HEX");

            // 4. Guardar en BD (primero limpiar la anterior)
            WorkerDao.clearHuella(workerId);
            boolean saved = WorkerDao.saveHuella(workerId, huellaHex);
            if (!saved) {
                return EnrollResult.error("No se pudo guardar en BD");
            }

            // 5. Recargar cache (equivalente a _load_workers_from_db() en Python)
            workersCache = WorkerDao.getWorkersWithHuella();
            FileLogger.i(TAG, "Huella registrada correctamente para ID:" + workerId);
            return EnrollResult.success(previewBase64);

        } catch (SQLException e) {
            FileLogger.e(TAG, "Error BD en enrollFingerprint: " + e.getMessage());
            return EnrollResult.error("Error BD: " + e.getMessage());
        } catch (Exception e) {
            FileLogger.e(TAG, "Error inesperado en enrollFingerprint", e);
            return EnrollResult.error(e.getMessage());
        } finally {
            sensor.setLed(false);
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Equivalente: _decode_huella() / bytes.fromhex() en Python. */
    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() < 8) return null;
        try {
            int len = hex.length();
            byte[] data = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                     + Character.digit(hex.charAt(i + 1), 16));
            }
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    /** Equivalente: template_bytes.hex() en Python. */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ─── Result types ─────────────────────────────────────────────────────────

    public static class ScanResult {
        public final boolean success;
        public final Worker  worker;
        public final int     score;
        public final String  previewBase64;
        public final String  error;

        private ScanResult(boolean success, Worker worker, int score, String preview, String error) {
            this.success       = success;
            this.worker        = worker;
            this.score         = score;
            this.previewBase64 = preview != null ? preview : "";
            this.error         = error;
        }

        static ScanResult success(Worker w, int score, String preview) {
            return new ScanResult(true, w, score, preview, null);
        }
        static ScanResult error(String msg) {
            return new ScanResult(false, null, 0, "", msg);
        }
        static ScanResult error(String msg, String preview) {
            return new ScanResult(false, null, 0, preview, msg);
        }
    }

    public static class EnrollResult {
        public final boolean success;
        public final String  error;
        public final String  previewBase64;

        private EnrollResult(boolean success, String error, String preview) {
            this.success       = success;
            this.error         = error;
            this.previewBase64 = preview != null ? preview : "";
        }
        static EnrollResult success()                       { return new EnrollResult(true,  null, ""); }
        static EnrollResult success(String preview)         { return new EnrollResult(true,  null, preview); }
        static EnrollResult error(String msg)               { return new EnrollResult(false, msg,  ""); }
        static EnrollResult error(String msg, String prev)  { return new EnrollResult(false, msg,  prev); }
    }
}
