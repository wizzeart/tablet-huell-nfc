package com.petronova.kiosk.hardware;

import com.machinezoo.sourceafis.FingerprintImage;
import com.machinezoo.sourceafis.FingerprintImageOptions;
import com.machinezoo.sourceafis.FingerprintMatcher;
import com.machinezoo.sourceafis.FingerprintTemplate;
import com.petronova.kiosk.util.FileLogger;

/**
 * Wrapper sobre SourceAFIS para extracción de minucias y matching biométrico
 * conforme a ISO/IEC 19794-2.
 *
 * SourceAFIS extrae bifurcaciones y terminaciones de crestas (minutiae) de
 * imágenes grayscale a 500 DPI y produce un score continuo. Un score ≥ 40
 * corresponde a FAR < 0.01% (umbral habitual en aplicaciones de identificación).
 *
 * Esta clase no tiene dependencias Android: puede usarse desde cualquier capa.
 */
public final class SourceAfisHelper {

    private static final String TAG = "SourceAfisHelper";

    /** DPI del sensor MX FPR-220K3-ISO (resolución estándar ISO para sensores ópticos). */
    private static final double SENSOR_DPI = 500.0;

    private SourceAfisHelper() {}

    /**
     * Extrae un template ISO/IEC 19794-2 a partir de la imagen raw capturada por el sensor.
     *
     * @param raw imagen grayscale 8-bit capturada por captureImage()
     * @return bytes del template serializado (formato SourceAFIS nativo), o null si falla
     */
    public static byte[] createTemplate(RawImage raw) {
        if (raw == null || raw.pixels == null || raw.width <= 0 || raw.height <= 0) {
            FileLogger.w(TAG, "createTemplate: imagen inválida o nula");
            return null;
        }
        try {
            FingerprintImage image = new FingerprintImage(
                raw.width, raw.height, raw.pixels,
                new FingerprintImageOptions().dpi(SENSOR_DPI));
            FingerprintTemplate template = new FingerprintTemplate(image);
            return template.toByteArray();
        } catch (Throwable t) {
            FileLogger.e(TAG, "Error creando template SourceAFIS: " + t.getMessage());
            return null;
        }
    }

    /**
     * Compara un template recién capturado (probe) contra uno almacenado (candidate).
     *
     * Ambos deben haber sido producidos por {@link #createTemplate}.
     *
     * @param probe     template capturado en este momento (live)
     * @param candidate template almacenado en BD
     * @return score de similitud (double 0–100+); ≥ 40 indica match genuino
     */
    public static double matchTemplates(byte[] probe, byte[] candidate) {
        if (probe == null || candidate == null) return 0.0;
        try {
            FingerprintTemplate probeTemplate     = new FingerprintTemplate(probe);
            FingerprintTemplate candidateTemplate = new FingerprintTemplate(candidate);
            return new FingerprintMatcher(probeTemplate).match(candidateTemplate);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Error en matching SourceAFIS: " + t.getMessage());
            return 0.0;
        }
    }
}
