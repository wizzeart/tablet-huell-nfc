package com.petronova.kiosk.ui.registration;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.petronova.kiosk.R;
import com.petronova.kiosk.config.AppConfig;
import com.petronova.kiosk.databinding.FragmentRegistrationBinding;
import com.petronova.kiosk.hardware.NfcController;
import com.petronova.kiosk.sensors.SensorCoordinator;
import com.petronova.kiosk.ui.main.MainActivity;
import com.petronova.kiosk.util.ToastSpeaker;

/**
 * Fragment de registro biométrico (huella y NFC).
 * Equivalente de fingerprintRegistrationScreen + nfcRegistrationScreen en registration.html
 * y las funciones de registration.js:
 *   searchWorkerForFingerprint(), startFingerprintCapture(),
 *   searchWorkerForNFC(), startNFCCapture().
 */
public class RegistrationFragment extends Fragment {

    public enum Mode { FINGERPRINT, NFC }

    private static final String ARG_MODE = "mode";

    /** Tiempo que permanece visible el modal flotante de preview antes de ocultarse. */
    private static final long PREVIEW_VISIBLE_MS = 3500L;

    // Scanner QR del hardware QS805 (réplica de QS805DEMO/MainActivity).
    /** Acción que dispara el escaneo del lector integrado. */
    private static final String ACTION_SCAN_TRIGGER = "ismart.intent.scandown";
    /** Acción del broadcast con el resultado del escaneo. */
    private static final String ACTION_SCAN_RESULT  = "com.qs.scancode";
    /** Cantidad de dígitos del carnet a tomar tras "CI:". */
    private static final int    CI_DIGITS           = 11;

    private FragmentRegistrationBinding binding;
    private RegistrationViewModel       viewModel;
    private Mode                        mode;
    private final Runnable              hidePreviewRunnable = this::hidePreviewModal;
    private BroadcastReceiver           qrScanReceiver;

    public static RegistrationFragment newInstance(Mode mode) {
        RegistrationFragment f = new RegistrationFragment();
        Bundle args = new Bundle();
        args.putString(ARG_MODE, mode.name());
        f.setArguments(args);
        return f;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentRegistrationBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mode = Mode.valueOf(getArguments().getString(ARG_MODE, Mode.FINGERPRINT.name()));
        viewModel = new ViewModelProvider(this).get(RegistrationViewModel.class);

        // Pausar loop de sensores durante registro (equivalente pause_loop en registration.js:156)
        SensorCoordinator.getInstance().pauseLoop();

        // Título según modo
        binding.tvRegTitle.setText(mode == Mode.FINGERPRINT
            ? "REGISTRO DE HUELLA"
            : "REGISTRO DE TARJETA NFC");

        // Buscar trabajador por CI
        binding.btnSearchWorker.setOnClickListener(v -> {
            String ci = binding.etCi.getText() != null ? binding.etCi.getText().toString().trim() : "";
            if (ci.isEmpty()) return;
            viewModel.searchWorker(ci);
        });

        // Iniciar captura
        binding.btnStartCapture.setOnClickListener(v -> {
            if (mode == Mode.FINGERPRINT) {
                viewModel.enrollFingerprint(AppConfig.SCAN_TIMEOUT_SECONDS);
            } else {
                startNfcCapture();
            }
        });

        binding.btnRegBack.setOnClickListener(v -> requireActivity().getSupportFragmentManager().popBackStack());

        // Escaneo del QR del carnet (lector QS805 integrado)
        setupQrScanner();

        observeViewModel();

        // Si modo NFC, activar reader
        if (mode == Mode.NFC) {
            setupNfcCapture();
        }
    }

    // ─── Observar ViewModel ──────────────────────────────────────────────────

    private void observeViewModel() {
        viewModel.getFoundWorker().observe(getViewLifecycleOwner(), worker -> {
            if (worker == null) {
                binding.llWorkerFound.setVisibility(View.GONE);
                return;
            }
            binding.tvFoundName.setText(worker.getFullName());
            binding.tvFoundCi.setText("CI: " + worker.carnet);
            binding.llWorkerFound.setVisibility(View.VISIBLE);
        });

        viewModel.getRegistrationState().observe(getViewLifecycleOwner(), state -> {
            switch (state) {
                case IDLE:
                    binding.progressCapture.setVisibility(View.GONE);
                    break;
                case CAPTURING:
                    binding.progressCapture.setVisibility(View.VISIBLE);
                    binding.tvCaptureInstruction.setVisibility(View.VISIBLE);
                    binding.tvCaptureInstruction.setText(mode == Mode.FINGERPRINT
                        ? getString(R.string.reg_place_finger)
                        : getString(R.string.reg_place_nfc));
                    break;
                case SUCCESS:
                    binding.progressCapture.setVisibility(View.GONE);
                    ToastSpeaker.show(requireContext(), mode == Mode.FINGERPRINT
                        ? getString(R.string.reg_success_fingerprint)
                        : getString(R.string.reg_success_nfc));
                    // Volver a main tras éxito (equivalente backToMain en registration.js)
                    binding.getRoot().postDelayed(
                        () -> requireActivity().getSupportFragmentManager().popBackStack(),
                        AppConfig.MODAL_AUTO_CLOSE_MS
                    );
                    break;
                case ERROR:
                    binding.progressCapture.setVisibility(View.GONE);
                    break;
            }
        });

        viewModel.getError().observe(getViewLifecycleOwner(), err -> {
            if (err != null) {
                binding.tvCaptureInstruction.setText(err);
                if (viewModel.getRegistrationState().getValue()
                        == RegistrationViewModel.RegistrationState.ERROR) {
                    ToastSpeaker.show(requireContext(), err);
                }
            }
        });

        // CI no encontrado → toast + voz (equivalente showFuelToast('Trabajador no encontrado.','error') en registration.js)
        viewModel.getSearchNotFound().observe(getViewLifecycleOwner(), msg -> {
            if (msg == null) return;
            ToastSpeaker.show(requireContext(), msg);
            viewModel.clearSearchNotFound();
        });

        // Preview de la huella capturada durante el registro → modal flotante arriba
        viewModel.getEnrollPreview().observe(getViewLifecycleOwner(), b64 -> {
            android.graphics.Bitmap bmp = com.petronova.kiosk.util.ImageUtils.decodeDataUri(b64);
            if (bmp != null) {
                showPreviewModal(bmp);
            } else {
                binding.ivRegPreview.setImageDrawable(null);
                hidePreviewModal();
            }
        });
    }

    // ─── Modal flotante de preview de la huella ──────────────────────────────

    /** Muestra el preview en un modal flotante (arriba) con entrada animada y auto-cierre. */
    private void showPreviewModal(@NonNull android.graphics.Bitmap bmp) {
        if (binding == null) return;
        View card = binding.cardRegPreview;
        binding.ivRegPreview.setImageBitmap(bmp);
        com.petronova.kiosk.util.ImageUtils.applyNeonFingerprint(binding.ivRegPreview);
        card.removeCallbacks(hidePreviewRunnable);

        // Entrada: aparece desde arriba con fundido
        card.setVisibility(View.VISIBLE);
        card.setAlpha(0f);
        card.setTranslationY(-40f);
        card.animate().alpha(1f).translationY(0f).setDuration(280L).start();

        // Se quita solo tras unos segundos
        card.postDelayed(hidePreviewRunnable, PREVIEW_VISIBLE_MS);
    }

    /** Oculta el modal flotante de preview con un fundido de salida. */
    private void hidePreviewModal() {
        if (binding == null) return;
        View card = binding.cardRegPreview;
        card.removeCallbacks(hidePreviewRunnable);
        if (card.getVisibility() != View.VISIBLE) return;
        card.animate().alpha(0f).translationY(-40f).setDuration(220L)
            .withEndAction(() -> {
                if (binding != null) binding.cardRegPreview.setVisibility(View.GONE);
            }).start();
    }

    // ─── Escaneo del QR del carnet (lector QS805) ────────────────────────────

    /**
     * Configura el botón y el receiver del lector QR integrado.
     * Réplica de QS805DEMO: se dispara con el broadcast {@link #ACTION_SCAN_TRIGGER}
     * y el resultado llega por {@link #ACTION_SCAN_RESULT} en el extra "data".
     */
    private void setupQrScanner() {
        qrScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getExtras() == null) return;
                String data = intent.getExtras().getString("data");
                onQrScanned(data);
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_SCAN_RESULT);
        ContextCompat.registerReceiver(requireContext(), qrScanReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED);

        binding.btnScanQr.setOnClickListener(v -> {
            // Dispara el escaneo del lector integrado
            requireContext().sendBroadcast(new Intent(ACTION_SCAN_TRIGGER));
        });
    }

    /**
     * Procesa el texto del QR: toma los {@link #CI_DIGITS} dígitos que siguen a "CI:",
     * los coloca en el input y pulsa "Buscar" automáticamente.
     */
    private void onQrScanned(@Nullable String data) {
        if (binding == null) return;
        String ci = extractCi(data);
        if (ci == null) {
            ToastSpeaker.show(requireContext(), getString(R.string.reg_qr_no_ci));
            return;
        }
        binding.etCi.setText(ci);
        binding.btnSearchWorker.performClick();
    }

    /**
     * Extrae los {@link #CI_DIGITS} dígitos que aparecen tras la primera ocurrencia de
     * "CI:" (ignorando espacios entre el prefijo y los dígitos). Devuelve null si no
     * se encuentra "CI:" o no hay dígitos suficientes.
     */
    @Nullable
    private static String extractCi(@Nullable String data) {
        if (data == null) return null;
        int idx = data.toUpperCase().indexOf("CI:");
        if (idx < 0) return null;
        StringBuilder sb = new StringBuilder(CI_DIGITS);
        for (int i = idx + 3; i < data.length() && sb.length() < CI_DIGITS; i++) {
            char c = data.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            } else if (sb.length() > 0) {
                // Ya empezaron los dígitos y aparece un separador no-dígito → fin del CI
                break;
            }
        }
        return sb.length() == CI_DIGITS ? sb.toString() : null;
    }

    // ─── NFC en modo registro ────────────────────────────────────────────────

    private void setupNfcCapture() {
        // El reader se activa cuando el usuario pulsa "Iniciar captura"
    }

    private void startNfcCapture() {
        MainActivity activity = (MainActivity) requireActivity();
        binding.progressCapture.setVisibility(View.VISIBLE);
        binding.tvCaptureInstruction.setVisibility(View.VISIBLE);
        binding.tvCaptureInstruction.setText(getString(R.string.reg_place_nfc));
        activity.getNfcController().startReading(uid -> {
            activity.getNfcController().stopReading();
            viewModel.enrollNfc(uid);
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mode == Mode.NFC) {
            ((MainActivity) requireActivity()).getNfcController().stopReading();
        }
        SensorCoordinator.getInstance().resumeLoop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (qrScanReceiver != null) {
            try {
                requireContext().unregisterReceiver(qrScanReceiver);
            } catch (IllegalArgumentException ignored) {
                // Ya estaba desregistrado.
            }
            qrScanReceiver = null;
        }
        if (binding != null) {
            binding.cardRegPreview.removeCallbacks(hidePreviewRunnable);
        }
        binding = null;
    }
}
