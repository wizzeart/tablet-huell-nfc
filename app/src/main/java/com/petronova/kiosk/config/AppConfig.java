package com.petronova.kiosk.config;

import android.content.Context;

import com.petronova.kiosk.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Centraliza todas las variables de configuración.
 * Las credenciales llegan vía BuildConfig (definidas en secrets.properties, gitignoreado).
 * La clave RSA se carga desde assets/private_key.pem (también gitignoreado).
 */
public final class AppConfig {

    private AppConfig() {}

    // ─── PostgreSQL ─────────────────────────────────────────────────────────
    public static final String DB_HOST     = BuildConfig.DB_HOST;
    public static final int    DB_PORT     = BuildConfig.DB_PORT;
    public static final String DB_NAME     = BuildConfig.DB_NAME;
    public static final String DB_USER     = BuildConfig.DB_USER;
    public static final String DB_PASSWORD = BuildConfig.DB_PASSWORD;

    // ─── API Petronova ───────────────────────────────────────────────────────
    public static final String PETRONOVA_BASE_URL = BuildConfig.PETRONOVA_BASE_URL;
    public static final String AGENT_ID           = BuildConfig.AGENT_ID;

    // ─── Seguridad / Acceso ──────────────────────────────────────────────────
    public static final String ADMIN_PASSWORD = BuildConfig.ADMIN_PASSWORD;
    public static final String EXIT_PIN       = BuildConfig.EXIT_PIN;

    // ─── Biometría ───────────────────────────────────────────────────────────
    public static final int MIN_CONFIDENCE    = BuildConfig.MIN_CONFIDENCE;
    public static final int TEMPLATE_SIZE     = 512;
    public static final int SECURITY_LEVEL    = 5;

    // ─── UI / UX ─────────────────────────────────────────────────────────────
    public static final long   INACTIVITY_TIMEOUT_MS  = 3 * 60 * 1000L;
    public static final long   MODAL_AUTO_CLOSE_MS    = 2500L;
    public static final long   FINGERPRINT_PREVIEW_MS = 2000L;
    public static final int    SCAN_TIMEOUT_SECONDS   = 30;
    public static final int    PIN_MAX_ATTEMPTS        = 3;
    public static final long   PIN_COOLDOWN_MS         = 10 * 60 * 1000L;
    public static final int    CI_MAX_LENGTH           = 20;
    public static final int    HTTP_TIMEOUT_SECONDS    = 30;

    /**
     * Carga la clave privada RSA desde assets/private_key.pem.
     * El archivo está en .gitignore y nunca se sube al repositorio.
     * Llama una vez al iniciar la app y cachea el resultado.
     */
    public static String loadPrivateKeyPem(Context context) throws IOException {
        try (InputStream is = context.getAssets().open("private_key.pem")) {
            byte[] bytes = is.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8).trim();
        }
    }
}
