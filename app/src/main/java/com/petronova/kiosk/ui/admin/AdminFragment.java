package com.petronova.kiosk.ui.admin;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.petronova.kiosk.config.AppConfig;
import com.petronova.kiosk.data.db.DbHealthChecker;
import com.petronova.kiosk.databinding.FragmentAdminBinding;
import com.petronova.kiosk.system.KioskManager;
import com.petronova.kiosk.system.SystemInfoProvider;
import com.petronova.kiosk.ui.main.MainActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Panel de administración del kiosko.
 * Equivalente de admin.js: checkAdminPassword(), showAdminConfigScreen(),
 * lockSystem(), restartSystem().
 *
 * Contraseña: AppConfig.ADMIN_PASSWORD ("Sacamuelas2026").
 * Máximo 3 intentos — al agotar bloquea el sistema.
 */
public class AdminFragment extends Fragment {

    private FragmentAdminBinding binding;
    private int                  attempts = 0;
    private boolean              unlocked = false;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentAdminBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.btnVerifyPassword.setOnClickListener(v -> verifyPassword());
        binding.btnAdminBack.setOnClickListener(v ->
            requireActivity().getSupportFragmentManager().popBackStack());
        binding.btnDbPing.setOnClickListener(v -> runDbPing());
        binding.btnRestart.setOnClickListener(v -> restartApp());
        binding.btnLockSystem.setOnClickListener(v -> lockSystem());
    }

    // ─── Verificación de contraseña (equivalente checkAdminPassword en admin.js) ─

    private void verifyPassword() {
        if (unlocked) return;
        String input = binding.etAdminPassword.getText() != null
            ? binding.etAdminPassword.getText().toString()
            : "";

        if (input.equals(AppConfig.ADMIN_PASSWORD)) {
            unlocked = true;
            binding.llAdminActions.setVisibility(View.VISIBLE);
            loadSystemInfo();
        } else {
            attempts++;
            if (attempts >= 3) {
                lockSystem();
            } else {
                binding.etAdminPassword.setText("");
                binding.etAdminPassword.setError(getString(
                    com.petronova.kiosk.R.string.admin_wrong_password) +
                    " (" + (3 - attempts) + " restantes)");
            }
        }
    }

    // ─── Info del sistema ─────────────────────────────────────────────────────

    private void loadSystemInfo() {
        bg.execute(() -> {
            String info = SystemInfoProvider.getFullInfo(requireContext());
            if (getActivity() != null) {
                requireActivity().runOnUiThread(() ->
                    binding.tvSystemInfoAdmin.setText(info));
            }
        });
    }

    // ─── Diagnóstico BD ───────────────────────────────────────────────────────

    private void runDbPing() {
        binding.btnDbPing.setEnabled(false);
        bg.execute(() -> {
            long latency = DbHealthChecker.ping();
            String result = latency >= 0
                ? "BD alcanzable — latencia: " + latency + " ms"
                : "BD NO alcanzable";
            if (getActivity() != null) {
                requireActivity().runOnUiThread(() -> {
                    binding.tvSystemInfoAdmin.setText(binding.tvSystemInfoAdmin.getText() + "\n" + result);
                    binding.btnDbPing.setEnabled(true);
                });
            }
        });
    }

    // ─── Reiniciar app (equivalente restartSystem en admin.js) ───────────────

    private void restartApp() {
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    // ─── Bloquear sistema (equivalente lockSystem en admin.js) ───────────────

    private void lockSystem() {
        KioskManager mgr = new KioskManager(requireActivity());
        mgr.stopKioskMode();
        requireActivity().finish();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bg.shutdown();
        binding = null;
    }
}
