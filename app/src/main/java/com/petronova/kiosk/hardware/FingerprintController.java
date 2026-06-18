package com.petronova.kiosk.hardware;

/**
 * Interfaz del controlador de huella dactilar.
 *
 * Esta es LA FRONTERA DE SUSTITUCIÓN del lector. Toda la lógica de negocio
 * (FingerprintService, ViewModels, UI) solo conoce esta interfaz.
 * Para cambiar el lector, se implementa esta interfaz con el nuevo SDK
 * sin tocar nada más.
 *
 * Equivalente Python: clase SecuGenFingerprint en secugen_fingerprint.py.
 * Cada método documenta su contraparte en el código Python original.
 */
public interface FingerprintController {

    /**
     * Inicializa el dispositivo y abre la conexión.
     * Equivalente: SecuGenFingerprint.init_device()
     *              → Create() + Init(SG_DEV_FDU03) + OpenDevice(0) + GetDeviceInfo()
     * @return true si el dispositivo se inicializó correctamente
     */
    boolean initDevice();

    /**
     * Cierra el dispositivo sin terminar la librería.
     * Equivalente: SecuGenFingerprint.close_device() → CloseDevice()
     */
    void closeDevice();

    /**
     * Libera los recursos de la librería del SDK.
     * Equivalente: SecuGenFingerprint.terminate() → Terminate()
     */
    void terminate();

    /**
     * Captura una imagen del sensor (bloqueante hasta timeout).
     * Equivalente: SecuGenFingerprint.capture_image(timeout_seconds)
     *              → poll GetImage() cada 0.1s hasta SGFDX_ERROR_NONE
     *
     * TODO[NUEVO-LECTOR]: reemplazar el poll de GetImage() por el mecanismo
     *   del nuevo SDK (callback, blocking read, etc.).
     *
     * @param timeoutSeconds tiempo máximo de espera
     * @return RawImage con píxeles y dimensiones, o null si timeout/error
     */
    RawImage captureImage(int timeoutSeconds);

    /**
     * Crea el template biométrico a partir de la imagen capturada.
     * Equivalente: SecuGenFingerprint.create_template(raw_image)
     *              → CreateSG400Template() → 400 bytes SG400
     *
     * TODO[NUEVO-LECTOR]: el nuevo SDK puede producir un template de tamaño
     *   y formato diferente. Ajustar AppConfig.TEMPLATE_SIZE en consecuencia.
     *   El formato almacenado en BD (hex string) se mantiene igual.
     *
     * @param raw imagen capturada por captureImage()
     * @return bytes del template, o null si falla
     */
    byte[] createTemplate(RawImage raw);

    /**
     * Compara dos templates y devuelve el resultado del matching.
     * Equivalente: SecuGenFingerprint.match_templates(t1, t2, secu_level)
     *              → MatchTemplate() + GetMatchingScore()
     *
     * TODO[NUEVO-LECTOR]: mapear secuLevel al concepto equivalente del nuevo SDK.
     *   El umbral de aceptación sigue siendo AppConfig.MIN_CONFIDENCE (sobre el score).
     *   Si el nuevo SDK usa escala diferente (0–1 float, 0–1000, etc.), ajustar
     *   MIN_CONFIDENCE en AppConfig o normalizar el score aquí.
     *
     * @param t1        template recién capturado
     * @param t2        template almacenado en BD
     * @param secuLevel nivel de seguridad (9 = SL_HIGHEST para SecuGen)
     * @return MatchResult con matched (bool) y score (int)
     */
    MatchResult matchTemplates(byte[] t1, byte[] t2, int secuLevel);

    /**
     * Enciende o apaga el LED del sensor (si el hardware lo soporta).
     * Equivalente: SecuGenFingerprint.set_led(on) → SetLedOn(bOn)
     *
     * TODO[NUEVO-LECTOR]: si el nuevo lector no tiene LED, implementar como no-op.
     *
     * @param on true para encender, false para apagar
     */
    void setLed(boolean on);

    /**
     * Convierte la imagen raw a un string Base64 (data URI) para previsualización.
     * Equivalente: SecuGenFingerprint.raw_to_bmp_base64(raw_image)
     *
     * TODO[NUEVO-LECTOR]: adaptar a la representación nativa del nuevo sensor.
     *   En Android se puede generar Bitmap directamente en lugar de BMP+base64.
     *
     * @param raw imagen capturada
     * @return data URI ("data:image/bmp;base64,...") o string vacío si falla
     */
    String rawToPreviewBase64(RawImage raw);

    /** Indica si el dispositivo está abierto y listo. */
    boolean isDeviceOpen();
}
