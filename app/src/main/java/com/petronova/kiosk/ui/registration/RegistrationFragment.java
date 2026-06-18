package com.petronova.kiosk.ui.registration;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

    private FragmentRegistrationBinding binding;
    private RegistrationViewModel       viewModel;
    private Mode                        mode;
    private final Runnable              hidePreviewRunnable = this::hidePreviewModal;

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
        if (binding != null) {
            binding.cardRegPreview.removeCallbacks(hidePreviewRunnable);
        }
        binding = null;
    }
}
