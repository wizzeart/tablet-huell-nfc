package com.petronova.kiosk.hardware.impl;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.zyapi.CommonApi;

import com.mx.finger.api.msc.MxIdMscFingerApiFactory;
import com.mx.finger.api.msc.MxMscBigFingerApi;
import com.mx.finger.common.MxImage;
import com.mx.finger.common.Result;
import com.mx.finger.utils.LogUtils;
import com.mx.finger.utils.RawBitmapUtils;

import com.petronova.kiosk.config.AppConfig;
import com.petronova.kiosk.hardware.FingerprintController;
import com.petronova.kiosk.hardware.MatchResult;
import com.petronova.kiosk.hardware.RawImage;
import com.petronova.kiosk.hardware.SourceAfisHelper;
import com.petronova.kiosk.util.FileLogger;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementación de {@link FingerprintController} para el lector de huella MX
 * (placa ZY) — SDK de MxMscFingerDemoID.
 *
 * Es la implementación concreta del "NUEVO-LECTOR" anticipado por la arquitectura:
 * toda la lógica de negocio (FingerprintService, ViewModels, UI) solo conoce la
 * interfaz {@link FingerprintController}, por lo que integrar este lector NO requiere
 * tocar nada más.
 *
 * Estrategia:
 *   - captureImage  → hardware MX: getFingerImageBig() obtiene imagen grayscale del sensor.
 *   - createTemplate → SourceAFIS (ISO/IEC 19794-2): extrae minucias de la imagen raw.
 *   - matchTemplates → SourceAFIS: FingerprintMatcher devuelve score continuo (0–100+).
 *   - GPIO {@link CommonApi} → energiza el módulo antes de usar el SDK.
 *
 * Notas importantes:
 *   - Los templates son serializaciones SourceAFIS (varios KB, tamaño variable).
 *   - El score es continuo; matched = score >= AppConfig.MIN_CONFIDENCE (default 40).
 *   - Todas las llamadas nativas van protegidas: ante cualquier Throwable se degrada
 *     de forma segura, nunca crashea.
 */
public class MxFingerprintController implements FingerprintController {

    private static final String TAG = "MxFingerprintCtrl";

    /** Pines GPIO que energizan el módulo de huella en la placa ZY (ver demo openGPIO/closeGPIO). */
    private static final int GPIO_PIN_A = 18;
    private static final int GPIO_PIN_B = 107;

    /** VID/PID del lector USB MX (= los de MxIdMscFingerApiFactory y device_filter.xml del demo). */
    private static final int USB_VID = 1025;
    private static final int USB_PID = 21547;

    /** Tiempo máximo de espera para que el kernel enumere el lector USB tras energizar el GPIO. */
    private static final long USB_ENUM_TIMEOUT_MS = 8000L;
    /** Tiempo máximo de espera para que el usuario/sistema conceda el permiso USB. */
    private static final long USB_PERMISSION_TIMEOUT_MS = 25000L;

    private static final String ACTION_USB_PERMISSION = "com.petronova.kiosk.USB_PERMISSION";

    private final Context context;

    private MxMscBigFingerApi api;
    private CommonApi          gpio;
    private boolean            deviceOpen = false;

    public MxFingerprintController(Context context) {
        this.context = context.getApplicationContext();
    }

    // ─── Ciclo de vida ───────────────────────────────────────────────────────

    @Override
    public boolean initDevice() {
        // Idempotente: el provider y FingerprintService.start() pueden llamarlo dos veces.
        // Evita re-crear la factory y re-reclamar el dispositivo USB.
        if (deviceOpen && api != null) {
            return true;
        }
        try {
            // 1. Energizar el módulo de huella vía GPIO (best-effort).
            gpioPowerOn();

            // 2. Esperar a que el kernel enumere el lector USB. El SDK busca el dispositivo
            //    al construir la factory; si lo creamos antes de que aparezca, falla con
            //    CODE_NO_DEVICE (-100). Tras el GPIO la enumeración puede tardar 1-3 s.
            UsbDevice device = waitForUsbDevice(USB_ENUM_TIMEOUT_MS);
            if (device == null) {
                FileLogger.w(TAG, "Lector USB (VID " + USB_VID + "/PID " + USB_PID
                        + ") no enumerado tras " + USB_ENUM_TIMEOUT_MS + "ms");
                deviceOpen = false;
                return false;
            }

            // 3. Garantizar permiso USB ANTES de construir la factory. Lo hacemos nosotros
            //    con PendingIntent mutable (el SDK usa flags antiguos que crashean en API 31+).
            if (!ensureUsbPermission(device)) {
                FileLogger.w(TAG, "Permiso USB no concedido para el lector MX");
                deviceOpen = false;
                return false;
            }

            // 4. Inicializar el SDK MX (ya hay dispositivo + permiso).
            LogUtils.logAble(true);
            MxIdMscFingerApiFactory factory = new MxIdMscFingerApiFactory(context);
            api = factory.getApi();

            if (api == null) {
                FileLogger.w(TAG, "SDK MX no devolvió api");
                deviceOpen = false;
                return false;
            }

            // 5. Validar comunicación con reintentos (el open/claim USB puede tardar un instante).
            for (int attempt = 1; attempt <= 4; attempt++) {
                Result<String> info = api.getDeviceInfo();
                if (info != null && info.isSuccess()) {
                    FileLogger.i(TAG, "Lector MX inicializado: " + info.data);
                    deviceOpen = true;
                    return true;
                }
                FileLogger.w(TAG, "getDeviceInfo intento " + attempt + " falló (code="
                        + (info != null ? info.code : "null") + ")");
                Thread.sleep(400);
            }

            FileLogger.w(TAG, "getDeviceInfo no respondió tras varios intentos — lector MX no disponible");
            deviceOpen = false;
            return false;

        } catch (Throwable t) {
            // UnsatisfiedLinkError (sin .so), USB no conectado, etc.
            FileLogger.e(TAG, "Error inicializando lector MX: " + t.getMessage());
            deviceOpen = false;
            return false;
        }
    }

    // ─── USB: enumeración + permiso ───────────────────────────────────────────

    /** Espera (poll) hasta que el lector USB VID/PID aparezca en el sistema, o null si timeout. */
    private UsbDevice waitForUsbDevice(long timeoutMs) {
        try {
            UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (um == null) return null;
            long deadline = SystemClock.elapsedRealtime() + timeoutMs;
            do {
                for (UsbDevice d : um.getDeviceList().values()) {
                    if (d.getVendorId() == USB_VID && d.getProductId() == USB_PID) {
                        FileLogger.i(TAG, "Lector USB MX detectado");
                        return d;
                    }
                }
                Thread.sleep(300);
            } while (SystemClock.elapsedRealtime() < deadline);
        } catch (Throwable t) {
            FileLogger.w(TAG, "Error esperando lector USB: " + t.getMessage());
        }
        return null;
    }

    /** Concede (o solicita y espera) el permiso de acceso al dispositivo USB. Bloqueante. */
    private boolean ensureUsbPermission(UsbDevice device) {
        try {
            UsbManager um = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (um == null) return false;
            if (um.hasPermission(device)) return true;

            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicBoolean granted = new AtomicBoolean(false);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                        granted.set(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
                        latch.countDown();
                    }
                }
            };

            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }

            try {
                int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? PendingIntent.FLAG_MUTABLE : 0;
                Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
                PendingIntent pi = PendingIntent.getBroadcast(context, 0, intent, piFlags);
                FileLogger.i(TAG, "Solicitando permiso USB para el lector MX...");
                um.requestPermission(device, pi);
                latch.await(USB_PERMISSION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } finally {
                try { context.unregisterReceiver(receiver); } catch (Throwable ignored) {}
            }

            boolean ok = granted.get() || um.hasPermission(device);
            FileLogger.i(TAG, "Permiso USB " + (ok ? "concedido" : "denegado"));
            return ok;
        } catch (Throwable t) {
            FileLogger.w(TAG, "Error solicitando permiso USB: " + t.getMessage());
            return false;
        }
    }

    @Override
    public void closeDevice() {
        deviceOpen = false;
        gpioPowerOff();
    }

    @Override
    public void terminate() {
        deviceOpen = false;
        api = null;
        gpioPowerOff();
    }

    // ─── Captura / template / matching ────────────────────────────────────────

    @Override
    public RawImage captureImage(int timeoutSeconds) {
        if (api == null) return null;
        try {
            Result<MxImage> result = api.getFingerImageBig(timeoutSeconds * 1000L);
            if (result == null || !result.isSuccess() || result.data == null) {
                FileLogger.d(TAG, "captureImage: sin imagen (code="
                        + (result != null ? result.code : "null") + ")");
                return null;
            }
            MxImage img = result.data;
            return new RawImage(img.data, img.width, img.height, 0);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Error en captureImage: " + t.getMessage());
            return null;
        }
    }

    @Override
    public byte[] createTemplate(RawImage raw) {
        // Extrae minucias ISO/IEC 19794-2 de la imagen grayscale usando SourceAFIS.
        // La RawImage ya contiene los píxeles capturados por captureImage(); no se
        // necesita ninguna operación adicional en el dispositivo.
        byte[] template = SourceAfisHelper.createTemplate(raw);
        if (template == null) {
            FileLogger.d(TAG, "createTemplate: SourceAFIS no pudo extraer minucias");
        } else {
            FileLogger.d(TAG, "createTemplate: template ISO 19794-2 generado (" + template.length + " bytes)");
        }
        return template;
    }

    @Override
    public MatchResult matchTemplates(byte[] t1, byte[] t2, int secuLevel) {
        // Matching ISO/IEC 19794-2 via SourceAFIS. t1 = probe (live), t2 = stored.
        // secuLevel no se usa: SourceAFIS gestiona internamente su propio umbral de similitud.
        if (t1 == null || t2 == null) {
            return new MatchResult(false, 0);
        }
        try {
            double score = SourceAfisHelper.matchTemplates(t1, t2);
            boolean matched = score >= AppConfig.MIN_CONFIDENCE;
            FileLogger.d(TAG, "matchTemplates(SourceAFIS): score=" + String.format("%.1f", score)
                    + ", threshold=" + AppConfig.MIN_CONFIDENCE + ", matched=" + matched);
            return new MatchResult(matched, (int) score);
        } catch (Throwable t) {
            FileLogger.e(TAG, "Error en matchTemplates: " + t.getMessage());
            return new MatchResult(false, 0);
        }
    }

    @Override
    public void setLed(boolean on) {
        // El lector MX no expone un LED simple; lamp() es best-effort.
        if (api == null) return;
        try {
            api.lamp(on ? 1 : 0, 0);
        } catch (Throwable ignored) {
            // Sin LED controlable → no-op.
        }
    }

    @Override
    public String rawToPreviewBase64(RawImage raw) {
        if (raw == null || raw.pixels == null) return "";
        try {
            Bitmap bmp = RawBitmapUtils.raw2Bimap(raw.pixels, raw.width, raw.height);
            if (bmp == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            return "data:image/png;base64," + b64;
        } catch (Throwable t) {
            FileLogger.w(TAG, "No se pudo generar preview MX: " + t.getMessage());
            return "";
        }
    }

    @Override
    public boolean isDeviceOpen() {
        return deviceOpen;
    }

    // ─── GPIO (réplica de openGPIO/closeGPIO del demo MxMscFingerDemoID) ──────

    /** Transfiere la alimentación al módulo de huella. Equivalente: openGPIO() del demo. */
    private void gpioPowerOn() {
        try {
            if (gpio == null) gpio = new CommonApi();
            gpio.setGpioDir(GPIO_PIN_A, 1);
            gpio.setGpioOut(GPIO_PIN_A, 1);
            Thread.sleep(200);
            gpio.setGpioDir(GPIO_PIN_B, 1);
            gpio.setGpioOut(GPIO_PIN_B, 1);
        } catch (Throwable t) {
            FileLogger.w(TAG, "GPIO power-on no disponible: " + t.getMessage());
        }
    }

    /** Devuelve la alimentación al USB. Equivalente: closeGPIO() del demo. */
    private void gpioPowerOff() {
        try {
            if (gpio == null) gpio = new CommonApi();
            gpio.setGpioDir(GPIO_PIN_A, 1);
            gpio.setGpioOut(GPIO_PIN_A, 0);
            Thread.sleep(200);
            gpio.setGpioDir(GPIO_PIN_B, 1);
            gpio.setGpioOut(GPIO_PIN_B, 0);
        } catch (Throwable t) {
            FileLogger.w(TAG, "GPIO power-off no disponible: " + t.getMessage());
        }
    }
}
