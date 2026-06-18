package com.petronova.kiosk.hardware.impl;

import android.util.Log;
import com.petronova.kiosk.hardware.FingerprintController;
import com.petronova.kiosk.hardware.MatchResult;
import com.petronova.kiosk.hardware.RawImage;

/**
 * Stub del controlador de huella. Permite que toda la lógica de negocio
 * compile y corra sin un lector físico.
 *
 * CUANDO EL USUARIO INFORME EL NUEVO SDK:
 *   1. Crear NuevoLectorController implements FingerprintController
 *   2. Implementar cada TODO[NUEVO-LECTOR] con el SDK real
 *   3. Cambiar FingerprintControllerProvider.create() para devolver la nueva impl
 *   4. Decidir estrategia de re-enrolamiento (ver §3.3 del plan)
 */
public class PlaceholderFingerprintController implements FingerprintController {

    private static final String TAG = "FingerprintCtrl";
    private boolean deviceOpen = false;

    // =====================================================================
    // TODO[NUEVO-LECTOR]: Importar el SDK del nuevo lector aquí.
    //   Ejemplo: import com.nuevofabricante.sdk.NuevoSdk;
    //            private NuevoSdk sdk;
    // Antes (SecuGen): import com.petronova.kiosk.secugen.PYSGFPLib;
    //                  → /usr/local/lib/libpysgfplib.so vía ctypes en Python
    // =====================================================================

    @Override
    public boolean initDevice() {
        // TODO[NUEVO-LECTOR]: sdk = new NuevoSdk();
        //   sdk.create(); sdk.init(DEVICE_ID); sdk.openDevice(0);
        //   Antes (SecuGen Python): Create() + Init(SG_DEV_FDU03) + OpenDevice(0) + GetDeviceInfo()
        Log.w(TAG, "initDevice() — stub, no hay lector conectado");
        deviceOpen = false;
        return false;
    }

    @Override
    public void closeDevice() {
        // TODO[NUEVO-LECTOR]: sdk.closeDevice();
        //   Antes (SecuGen Python): CloseDevice()
        deviceOpen = false;
    }

    @Override
    public void terminate() {
        // TODO[NUEVO-LECTOR]: sdk.terminate();
        //   Antes (SecuGen Python): Terminate()
    }

    @Override
    public RawImage captureImage(int timeoutSeconds) {
        // TODO[NUEVO-LECTOR]: llamar al método de captura del nuevo SDK.
        //   Puede ser bloqueante o basado en callback.
        //   Antes (SecuGen Python): poll GetImage() cada 0.1s hasta SGFDX_ERROR_NONE,
        //   luego GetImageQuality(). Devolver RawImage(bytes, width, height, quality).
        Log.w(TAG, "captureImage() — stub, devuelve null");
        return null;
    }

    @Override
    public byte[] createTemplate(RawImage raw) {
        // TODO[NUEVO-LECTOR]: sdk.createTemplate(raw.pixels) → byte[].
        //   Antes (SecuGen Python): CreateSG400Template() → 400 bytes SG400.
        //   Si el nuevo lector produce templates de tamaño diferente:
        //     → actualizar AppConfig.TEMPLATE_SIZE
        //     → verificar compatibilidad con huellas ya en BD (requiere re-enrolamiento)
        Log.w(TAG, "createTemplate() — stub, devuelve null");
        return null;
    }

    @Override
    public MatchResult matchTemplates(byte[] t1, byte[] t2, int secuLevel) {
        // TODO[NUEVO-LECTOR]: sdk.match(t1, t2) → (matched, score).
        //   Antes (SecuGen Python): MatchTemplate() + GetMatchingScore().
        //   Si el nuevo SDK devuelve un score en escala diferente (0.0-1.0, 0-1000, etc.),
        //   normalizar aquí a 0-100 o ajustar AppConfig.MIN_CONFIDENCE a la nueva escala.
        Log.w(TAG, "matchTemplates() — stub, devuelve no-match");
        return new MatchResult(false, 0);
    }

    @Override
    public void setLed(boolean on) {
        // TODO[NUEVO-LECTOR]: sdk.setLed(on);
        //   Si el nuevo lector no tiene LED, dejar este método como no-op.
        //   Antes (SecuGen Python): SetLedOn(bOn)
    }

    @Override
    public String rawToPreviewBase64(RawImage raw) {
        // TODO[NUEVO-LECTOR]: convertir la imagen del nuevo lector a base64 o Bitmap.
        //   En Android es más eficiente devolver un Bitmap directamente que un data URI.
        //   Antes (SecuGen Python): raw_to_bmp_base64() → construía BMP 8-bit manualmente
        //   con paleta de grises y lo codificaba en base64.
        return "";
    }

    @Override
    public boolean isDeviceOpen() {
        return deviceOpen;
    }
}
