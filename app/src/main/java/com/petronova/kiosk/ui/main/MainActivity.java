package com.petronova.kiosk.ui.main;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.petronova.kiosk.R;
import com.petronova.kiosk.config.AppConfig;
import com.petronova.kiosk.data.db.DbHealthChecker;
import com.petronova.kiosk.databinding.ActivityMainBinding;
import com.petronova.kiosk.hardware.impl.AndroidNfcController;
import com.petronova.kiosk.service.FingerprintService;
import com.petronova.kiosk.system.InactivityTimer;
import com.petronova.kiosk.system.KioskManager;
import com.petronova.kiosk.system.SystemInfoProvider;
import com.petronova.kiosk.ui.admin.AdminFragment;
import com.petronova.kiosk.ui.config.LocationSelectorFragment;
import com.petronova.kiosk.ui.config.PisteroFragment;
import com.petronova.kiosk.ui.config.TankSelectorFragment;
import com.petronova.kiosk.ui.fuel.FuelDispenseFragment;
import com.petronova.kiosk.ui.registration.RegistrationFragment;
import com.petronova.kiosk.ui.scan.ScanFragment;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity principal y único punto de entrada.
 * Gestiona:
 *   - Navegación entre Fragments (sustituye a showMainScreen/startScanning/etc. de main.js)
 *   - Modo kiosko (Lock Task Mode, pantalla completa)
 *   - Timer de inactividad (3 min → vuelve a MainScreenFragment)
 *   - Ping periódico a BD (10 s)
 *   - Info del sistema en barra superior
 *   - Ciclo de vida del FingerprintService
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private InactivityTimer     inactivityTimer;
    private KioskManager        kioskManager;
    private AndroidNfcController nfcController;
    private final Handler       uiHandler  = new Handler(Looper.getMainLooper());
    private final ExecutorService bgExecutor = Executors.newCachedThreadPool();

    private static final long DB_PING_INTERVAL_MS = 10_000L;

    // Contador de taps en logo para mostrar botón admin
    private int logoTapCount = 0;
    private static final int LOGO_TAPS_TO_SHOW_ADMIN = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Pantalla completa sin barra de estado ni navegación; nunca apagar la pantalla
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Modo kiosko
        kioskManager = new KioskManager(this);
        kioskManager.startKioskMode();

        // Iniciar servicio de huella en background
        bgExecutor.execute(() -> FingerprintService.getInstance().start());

        // Timer de inactividad (equivalente INACTIVITY_TIMEOUT en globals.js + kiosk.js)
        inactivityTimer = new InactivityTimer(AppConfig.INACTIVITY_TIMEOUT_MS, this::onInactivityTimeout);
        inactivityTimer.start();

        // NFC
        nfcController = new AndroidNfcController(this);

        // Mostrar pantalla principal
        if (savedInstanceState == null) {
            showMainScreen();
        }

        // Ping DB periódico
        startDbPingLoop();
        // Info sistema
        startSystemInfoLoop();
    }

    // ─── Navegación (equivalente a showMainScreen/startScanning/etc. en main.js) ─

    public void showMainScreen() {
        navigateTo(new MainScreenFragment(), false);
    }

    /** Cierra todas las pantallas del back stack y vuelve al inicio (pantalla principal). */
    public void goToMainScreen() {
        if (isFinishing() || isDestroyed() || getSupportFragmentManager().isStateSaved()) {
            return;
        }
        getSupportFragmentManager().popBackStack(null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        showMainScreen();
    }

    public void showScanScreen() {
        navigateTo(new ScanFragment(), true);
    }

    public void showRegistrationScreen(RegistrationFragment.Mode mode) {
        navigateTo(RegistrationFragment.newInstance(mode), true);
    }

    public void showAdminScreen() {
        navigateTo(new AdminFragment(), true);
    }

    /** Equivalente: showTankSelector() en scanning.js:1676 */
    public void showTankSelector() {
        navigateTo(new TankSelectorFragment(), true);
    }

    /** Equivalente: showLocationSelector() en scanning.js:1837 */
    public void showLocationSelector() {
        navigateTo(new LocationSelectorFragment(), true);
    }

    /** Equivalente: openPisteroModal() en scanning.js:1969 */
    public void showPisteroScreen() {
        navigateTo(new PisteroFragment(), true);
    }

    /** Equivalente: showFuelModal() tras checkFuelAssignment() en scanning.js. */
    public void showFuelDispenseScreen(int workerId, String workerName, String workerCi) {
        navigateTo(FuelDispenseFragment.newInstance(workerId, workerName, workerCi), true);
    }

    private void navigateTo(Fragment fragment, boolean addToBackStack) {
        FragmentTransaction tx = getSupportFragmentManager().beginTransaction()
            .replace(R.id.fragment_container, fragment);
        if (addToBackStack) tx.addToBackStack(null);
        tx.commit();
    }

    // ─── Inactividad ─────────────────────────────────────────────────────────

    private void onInactivityTimeout() {
        runOnUiThread(() -> {
            // Si la actividad está en segundo plano ya guardó su estado:
            // popBackStack()/commit() lanzarían IllegalStateException
            // ("Can not perform this action after onSaveInstanceState").
            if (isFinishing() || isDestroyed()
                    || getSupportFragmentManager().isStateSaved()) {
                return;
            }
            // Limpiar el back stack y volver a main (equivalente handleInactivityTimeout en kiosk.js)
            getSupportFragmentManager().popBackStack(null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
            showMainScreen();
        });
    }

    /** Cualquier evento de usuario resetea el timer de inactividad. */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (inactivityTimer != null) inactivityTimer.reset();
    }

    // ─── DB Ping (equivalente al polling de /db/ping cada 10 s en index.html) ─

    private void startDbPingLoop() {
        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                long latency = DbHealthChecker.ping();
                String status = latency >= 0
                    ? "● BD " + latency + "ms"
                    : "● Sin BD";
                int color = latency >= 0
                    ? getColor(R.color.color_success)
                    : getColor(R.color.color_error);
                runOnUiThread(() -> {
                    binding.tvDbStatus.setText(status);
                    binding.tvDbStatus.setTextColor(color);
                });
                uiHandler.postDelayed(this, DB_PING_INTERVAL_MS);
            }
        });
    }

    // ─── Info sistema (equivalente /system_info polling) ─────────────────────

    private void startSystemInfoLoop() {
        bgExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String info = SystemInfoProvider.getStatusLine(MainActivity.this);
                runOnUiThread(() -> binding.tvSystemInfo.setText(info));
                uiHandler.postDelayed(this, 60_000L); // actualizar cada minuto
            }
        });
    }

    // ─── NFC para uso externo (ScanFragment / RegistrationFragment) ──────────

    public AndroidNfcController getNfcController() {
        return nfcController;
    }

    // ─── Ciclo de vida ───────────────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        kioskManager.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        kioskManager.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        inactivityTimer.stop();
        FingerprintService.getInstance().stop();
        bgExecutor.shutdown();
    }

    @Override
    public void onBackPressed() {
        // Bloqueado en modo kiosko — no permitir salir por botón atrás
        // El admin puede salir desde el panel de administración
    }
}
