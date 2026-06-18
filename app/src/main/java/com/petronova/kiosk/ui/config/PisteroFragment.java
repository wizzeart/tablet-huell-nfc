package com.petronova.kiosk.ui.config;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.petronova.kiosk.R;
import com.petronova.kiosk.config.AppConfig;
import com.petronova.kiosk.data.local.LocalConfigStore;
import com.petronova.kiosk.data.model.Worker;
import com.petronova.kiosk.databinding.FragmentPisteroBinding;
import com.petronova.kiosk.network.PetronovaApiClient;
import com.petronova.kiosk.service.FingerprintService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Identificación de pistero por huella dactilar.
 * Equivalente del modal #pisteroModal + openPisteroModal() / startPisteroScan() en scanning.js:1969.
 *
 * Flujo de escaneo:
 *   1. FingerprintService.scanFingerprint() — captura y matching local
 *   2. PetronovaApiClient.verificarPisteroUbicacion() — valida rol en API externa
 *   3. LocalConfigStore.savePistero() — persiste el pistero (≡ POST /pistero)
 *
 * Flujo de borrado:
 *   1. LocalConfigStore.clearPistero() (≡ DELETE /pistero)
 */
public class PisteroFragment extends Fragment {

    private FragmentPisteroBinding binding;
    private LocalConfigStore       store;
    private boolean                scanning = false;
    private final ExecutorService  bg = Executors.newSingleThreadExecutor();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentPisteroBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        store = new LocalConfigStore(requireContext());

        binding.btnPisteroClose.setOnClickListener(v -> {
            FingerprintService.getInstance().cancel();
            requireActivity().getSupportFragmentManager().popBackStack();
        });

        binding.btnScanFingerprint.setOnClickListener(v -> startScan());
        binding.ivFingerprint.setOnClickListener(v -> startScan());
        binding.btnClearPistero.setOnClickListener(v -> clearPistero());

        loadCurrentPistero();
    }

    // ─── Pistero activo (≡ GET /pistero al abrir el modal) ──────────────────

    private void loadCurrentPistero() {
        LocalConfigStore.PisteroData p = store.getPistero();
        if (p != null) {
            binding.tvCurrentPistero.setText(getString(R.string.pistero_active_prefix) + p.getFullName());
            binding.tvCurrentPistero.setTextColor(getColor(R.color.color_pistero));
        } else {
            binding.tvCurrentPistero.setText(getString(R.string.pistero_no_active));
            binding.tvCurrentPistero.setTextColor(getColor(R.color.color_text_secondary));
        }
    }

    // ─── Escanear huella (≡ startPisteroScan() en scanning.js:2014) ─────────

    private void startScan() {
        if (scanning) return;
        scanning = true;
        binding.btnScanFingerprint.setEnabled(false);
        setStatus(getString(R.string.pistero_scan_place_finger), R.color.color_warning);
        setFingerprintTint(R.color.color_warning);

        bg.execute(() -> {
            // 1. Escaneo de huella
            FingerprintService.ScanResult result =
                FingerprintService.getInstance().scanFingerprint(AppConfig.SCAN_TIMEOUT_SECONDS);

            if (!result.success) {
                postError(getString(R.string.pistero_not_recognized));
                return;
            }

            Worker worker = result.worker;
            postUi(() -> setStatus(
                getString(R.string.pistero_verifying) + " " + worker.nombre + "…",
                R.color.color_warning));

            // 2. Obtener ubicacion_id
            String ubicIdStr = store.getUbicacionId();
            int ubicacionId;
            try {
                ubicacionId = Integer.parseInt(ubicIdStr);
            } catch (NumberFormatException e) {
                postError(getString(R.string.pistero_no_location));
                return;
            }

            // 3. Verificar rol de pistero en la API externa
            // (≡ GET /proxy/verificar-pistero-ubicacion)
            PetronovaApiClient.ApiResult verification =
                PetronovaApiClient.getInstance().verificarPisteroUbicacion(worker.id, ubicacionId);

            if (!verification.success) {
                postError(getString(R.string.pistero_api_error));
                return;
            }

            // La API externa devuelve: { "data": true } o { "data": { "data": true } }
            boolean isPistero = extractBooleanData(verification.data);

            if (!isPistero) {
                postError(getString(R.string.pistero_not_role));
                return;
            }

            // 4. Guardar pistero localmente (≡ POST /pistero)
            LocalConfigStore.PisteroData pisteroData = new LocalConfigStore.PisteroData(
                worker.id, worker.nombre, worker.apellidos);
            store.savePistero(pisteroData);

            postUi(() -> {
                setStatus(getString(R.string.pistero_identified_prefix) + pisteroData.getFullName(),
                    R.color.color_pistero);
                setFingerprintTint(R.color.color_pistero);
                binding.tvCurrentPistero.setText(
                    getString(R.string.pistero_active_prefix) + pisteroData.getFullName());
                binding.tvCurrentPistero.setTextColor(getColor(R.color.color_pistero));
                scanning = false;
                binding.btnScanFingerprint.setEnabled(true);

                // Cerrar automáticamente después de 1.5 s (≡ setTimeout → closePisteroModal)
                binding.getRoot().postDelayed(() -> {
                    if (getActivity() != null)
                        requireActivity().getSupportFragmentManager().popBackStack();
                }, 1500);
            });
        });
    }

    // ─── Quitar pistero (≡ clearPistero() + DELETE /pistero) ────────────────

    private void clearPistero() {
        store.clearPistero();
        binding.tvCurrentPistero.setText(getString(R.string.pistero_no_active));
        binding.tvCurrentPistero.setTextColor(getColor(R.color.color_text_secondary));
        setStatus(getString(R.string.pistero_cleared), R.color.color_text_secondary);
        setFingerprintTint(R.color.color_primary);
    }

    // ─── Parseo de respuesta de verificarPisteroUbicacion ───────────────────

    private boolean extractBooleanData(JsonElement data) {
        try {
            if (data.isJsonPrimitive()) return data.getAsBoolean();
            JsonObject obj = data.getAsJsonObject();
            if (obj.has("data")) {
                JsonElement inner = obj.get("data");
                if (inner.isJsonPrimitive()) return inner.getAsBoolean();
                if (inner.isJsonObject() && inner.getAsJsonObject().has("data")) {
                    return inner.getAsJsonObject().get("data").getAsBoolean();
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ─── Helpers UI ──────────────────────────────────────────────────────────

    private void setStatus(String msg, int colorRes) {
        binding.tvPisteroStatus.setText(msg);
        binding.tvPisteroStatus.setTextColor(getColor(colorRes));
        com.petronova.kiosk.audio.TtsManager.getInstance().speak(msg);
    }

    private void setFingerprintTint(int colorRes) {
        binding.ivFingerprint.setColorFilter(getColor(colorRes));
    }

    private void postUi(Runnable r) {
        if (getActivity() != null) requireActivity().runOnUiThread(r);
    }

    private void postError(String msg) {
        postUi(() -> {
            setStatus(msg, R.color.color_error);
            setFingerprintTint(R.color.color_error);
            scanning = false;
            binding.btnScanFingerprint.setEnabled(true);
        });
    }

    private int getColor(int resId) {
        return requireContext().getColor(resId);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        FingerprintService.getInstance().cancel();
        bg.shutdown();
        binding = null;
    }
}
