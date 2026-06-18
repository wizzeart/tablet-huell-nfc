# Petronova Kiosk — APK Android

Migración del sistema Python (FastAPI + HTML/JS) a una APK Android nativa (Java, MVVM).

## Estructura del proyecto

```
app/src/main/java/com/petronova/kiosk/
├── KioskApplication.java           Clase Application, preloads JDBC driver
├── config/
│   └── AppConfig.java              Equivalente de config.py — todas las constantes
├── data/
│   ├── db/
│   │   ├── DbConnectionFactory.java  get_db_connection() de fingerprint_db.py
│   │   ├── WorkerDao.java            Todas las queries SQL (1:1 fingerprint_db.py)
│   │   └── DbHealthChecker.java      Ping TCP a PostgreSQL (GET /db/ping)
│   ├── model/
│   │   └── Worker.java               Modelo de trabajador
│   └── repo/
│       └── WorkerRepository.java     Repositorio con LiveData
├── hardware/
│   ├── FingerprintController.java    INTERFAZ del lector — única frontera a sustituir
│   ├── NfcController.java            Interfaz NFC
│   ├── FacialController.java         Interfaz facial (fuera de alcance, stub)
│   ├── FuelDispenserController.java  Interfaz dispensador (fuera de alcance, stub)
│   ├── RawImage.java                 Imagen del sensor (equivalente cImgBuf)
│   ├── MatchResult.java              (matched, score) del matching
│   └── impl/
│       ├── PlaceholderFingerprintController.java   Stub con TODO[NUEVO-LECTOR]
│       ├── FingerprintControllerProvider.java      Fábrica — CAMBIAR AQUÍ al integrar lector
│       └── AndroidNfcController.java               NFC nativo (NfcAdapter.enableReaderMode)
├── service/
│   └── FingerprintService.java       Equivalente de SecuGenService (scan/enroll/matching)
├── sensors/
│   └── SensorCoordinator.java        Exclusión mutua (equivalente /sensor/* endpoints)
├── network/
│   └── PetronovaApiClient.java       Proxy RSA firmado a API Petronova (equivalente /proxy/*)
├── audio/
│   ├── TtsManager.java               TTS Android (equivalente tts_service.py + audio.js)
│   └── SoundManager.java             Beeps (equivalente playSuccessBeep etc. en audio.js)
├── system/
│   ├── InactivityTimer.java          Timer 3 min → vuelve a main (kiosk.js)
│   ├── KioskManager.java             Lock Task Mode Android (equivalente kiosk.js)
│   ├── SystemInfoProvider.java       IP/hora/uptime (equivalente /system_info)
│   └── BootReceiver.java             Autoarranque al encender
└── ui/
    ├── StatusDialog.java             Modales éxito/error con autocierre 2.5s y TTS
    ├── main/
    │   ├── MainActivity.java         Activity única: navegación, kiosko, DB ping, NFC
    │   └── MainScreenFragment.java   Pantalla principal (mainScreen en main_screen.html)
    ├── scan/
    │   ├── ScanFragment.java         Pantalla de escaneo (scanningScreen)
    │   ├── ScanViewModel.java        Lógica de escaneo biométrico
    │   └── PinDialogFragment.java    Modal PIN para salir (PIN_CODE=11235, 3 intentos)
    ├── registration/
    │   ├── RegistrationFragment.java Registro huella/NFC (fingerprintRegistrationScreen)
    │   └── RegistrationViewModel.java
    └── admin/
        └── AdminFragment.java        Panel admin (ADMIN_PASSWORD=Sacamuelas2026)
```

## Dependencias clave (app/build.gradle)

| Librería | Propósito |
|----------|-----------|
| `org.postgresql:postgresql:42.7.3` | JDBC directo a PostgreSQL |
| `com.squareup.okhttp3:okhttp:4.12.0` | HTTP para API Petronova |
| `org.bouncycastle:bcprov-jdk18on:1.78.1` | RSA PKCS#1 para firma del token |
| `org.bouncycastle:bcpkix-jdk18on:1.78.1` | PEMParser para leer la clave privada |
| `com.google.code.gson:gson:2.11.0` | JSON serialización |
| `androidx.lifecycle:lifecycle-viewmodel/livedata` | MVVM |

## Para integrar el nuevo lector de huellas

1. Buscar todos los `TODO[NUEVO-LECTOR]` en el proyecto (hay ~12 puntos).
2. Crear `hardware/impl/NuevoLectorController.java` implementando `FingerprintController`.
3. Cambiar `FingerprintControllerProvider.create()` para devolver la nueva impl.
4. Decidir estrategia de re-enrolamiento (las huellas SecuGen en BD no son compatibles).

## Build

```bash
# En Android Studio: Open > huella-nfc-facial-apk > Build > Make Project
# O desde línea de comandos:
./gradlew assembleDebug
```

## Configuración de kiosko (Lock Task Mode)

```bash
adb shell dpm set-device-owner com.petronova.kiosk/.system.KioskDeviceAdminReceiver
```
O configurar el MDM para añadir `com.petronova.kiosk` a la whitelist de Lock Task.
