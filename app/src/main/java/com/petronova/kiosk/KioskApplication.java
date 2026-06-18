package com.petronova.kiosk;

import android.app.Application;
import com.petronova.kiosk.audio.TtsManager;
import com.petronova.kiosk.data.db.DbConnectionFactory;
import com.petronova.kiosk.network.PetronovaApiClient;
import com.petronova.kiosk.util.CrashHandler;
import com.petronova.kiosk.util.FileLogger;

public class KioskApplication extends Application {

    private static KioskApplication instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Inicializar logs y captura de errores
        FileLogger.init(this);
        CrashHandler.init();

        FileLogger.i("APP", "Iniciando Petronova Kiosk...");

        // Pre-carga el driver JDBC de PostgreSQL para evitar ClassNotFoundException en hilos de fondo.
        DbConnectionFactory.preloadDriver();

        // Inicializa el motor TTS (voz) — equivalente a gTTS en la app web.
        // Sin esto, TtsManager.speak() es no-op (el motor nunca queda listo).
        TtsManager.getInstance().init(this);

        // Carga la clave RSA desde assets/private_key.pem (gitignoreado)
        try {
            PetronovaApiClient.init(this);
        } catch (java.io.IOException e) {
            FileLogger.e("APP", "Error cargando clave RSA desde assets: " + e.getMessage());
        }
    }

    public static KioskApplication getInstance() {
        return instance;
    }
}
