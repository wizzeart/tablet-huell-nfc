package com.petronova.kiosk.ui.scan;

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
import com.petronova.kiosk.databinding.FragmentScanBinding;
import com.petronova.kiosk.hardware.NfcController;
import com.petronova.kiosk.sensors.SensorCoordinator;
import com.petronova.kiosk.ui.main.MainActivity;
import com.petronova.kiosk.util.ToastSpeaker;

/**
 * Pantalla de escaneo biométrico.
 * Equivalente de scanningScreen + scanning.js (startFingerprintScan, startNFCScan,
 * showWorkerData, PIN modal, sensor indicator).
 */
public class ScanFragment extends Fragment {

    private static final String ACTION_SCAN_RESULT = "com.qs.scancode";

    private FragmentScanBinding binding;
    private ScanViewModel       viewModel;
    private boolean             fuelNavigationDone = false;
    private BroadcastReceiver   qrScanReceiver;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentScanBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(ScanViewModel.class);
        observeViewModel();

        // Animar el SVG de la huella
        android.view.animation.Animation pulse = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.neon_pulse);
        binding.ivScanIcon.startAnimation(pulse);

        binding.btnBackPin.setOnClickListener(v -> goBackToHome());
    }

    @Override
    public void onResume() {
        super.onResume();
        MainActivity activity = (MainActivity) requireActivity();
        SensorCoordinator.getInstance().activateNfc();
        activity.getNfcController().startReading(uid -> viewModel.onNfcTagDetected(uid));

        // Registrar receptor del escáner QR físico (hardware QS805)
        qrScanReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getExtras() == null) return;
                viewModel.onQrScanned(intent.getExtras().getString("data"));
            }
        };
        ContextCompat.registerReceiver(requireContext(), qrScanReceiver,
                new IntentFilter(ACTION_SCAN_RESULT), ContextCompat.RECEIVER_EXPORTED);

        // Iniciar el escaneo de huella al entrar (y al volver de otra pantalla).
        startScan();
    }

    @Override
    public void onPause() {
        super.onPause();
        MainActivity activity = (MainActivity) requireActivity();
        activity.getNfcController().stopReading();
        SensorCoordinator.getInstance().deactivateAll();
        if (qrScanReceiver != null) {
            requireContext().unregisterReceiver(qrScanReceiver);
            qrScanReceiver = null;
        }
        // Cancelar el escaneo de huella para liberar el lock del sensor: si el trabajador
        // se identificó por NFC/QR, el bucle de huella seguiría ocupando el sensor y la
        // siguiente pantalla (confirmación de combustible) recibiría "Sensor ocupado".
        viewModel.cancelScan();
    }

    // ─── Observar ViewModel ──────────────────────────────────────────────────

    private void observeViewModel() {
        viewModel.getState().observe(getViewLifecycleOwner(), state -> {
            switch (state) {
                case SCANNING_FINGERPRINT:
                    binding.tvScanInstruction.setText(getString(R.string.scan_waiting_fingerprint));
                    binding.tvSensorIndicator.setText("● HUELLA");
                    binding.tvSensorIndicator.setTextColor(getColor(R.color.sensor_active));
                    binding.llWorkerData.setVisibility(View.GONE);
                    break;
                case SCANNING_NFC:
                    binding.tvScanInstruction.setText(getString(R.string.scan_waiting_nfc));
                    binding.tvSensorIndicator.setText("● NFC");
                    binding.tvSensorIndicator.setTextColor(getColor(R.color.sensor_active));
                    break;
                case SCANNING_QR:
                    binding.tvScanInstruction.setText(getString(R.string.scan_waiting_qr));
                    binding.tvSensorIndicator.setText("● QR");
                    binding.tvSensorIndicator.setTextColor(getColor(R.color.sensor_active));
                    binding.llWorkerData.setVisibility(View.GONE);
                    break;
                case SUCCESS:
                    binding.tvSensorIndicator.setTextColor(getColor(R.color.sensor_success));
                    break;
                case ERROR:
                    binding.tvSensorIndicator.setTextColor(getColor(R.color.sensor_error));
                    // Reiniciar escaneo tras 2.5 s
                    binding.getRoot().postDelayed(this::startScan, AppConfig.MODAL_AUTO_CLOSE_MS);
                    break;
                case CANCELLED:
                    binding.tvSensorIndicator.setTextColor(getColor(R.color.sensor_idle));
                    break;
            }
        });

        viewModel.getDetectedWorker().observe(getViewLifecycleOwner(), worker -> {
            if (worker == null || fuelNavigationDone) return;
            fuelNavigationDone = true;
            binding.llWorkerData.setVisibility(View.VISIBLE);
            binding.tvWorkerName.setText(worker.getFullName());
            binding.tvWorkerCi.setText("CI: " + worker.carnet);
            binding.tvScanInstruction.setText(getString(R.string.scan_success));
            ToastSpeaker.show(requireContext(), getString(R.string.tts_welcome, worker.getFullName()));
            binding.getRoot().postDelayed(() -> {
                if (binding == null || !isAdded()) return;
                ((MainActivity) requireActivity()).showFuelDispenseScreen(
                    worker.id, worker.getFullName(), worker.carnet);
            }, 1200L);
        });

        viewModel.getErrorMessage().observe(getViewLifecycleOwner(), msg -> {
            if (msg != null) {
                binding.tvScanStatus.setText(msg);
                binding.tvScanStatus.setVisibility(View.VISIBLE);
                com.petronova.kiosk.audio.TtsManager.getInstance().speak(msg);
            }
        });

        // Preview de la huella capturada
        viewModel.getFingerprintPreview().observe(getViewLifecycleOwner(), b64 -> {
            android.graphics.Bitmap bmp = com.petronova.kiosk.util.ImageUtils.decodeDataUri(b64);
            if (bmp != null) {
                binding.ivFingerprintPreview.setImageBitmap(bmp);
                com.petronova.kiosk.util.ImageUtils.applyNeonFingerprint(binding.ivFingerprintPreview);
                binding.ivFingerprintPreview.setVisibility(View.VISIBLE);
            } else {
                binding.ivFingerprintPreview.setImageDrawable(null);
                binding.ivFingerprintPreview.setVisibility(View.GONE);
            }
        });
    }

    // ─── Iniciar escaneo ─────────────────────────────────────────────────────

    private void startScan() {
        fuelNavigationDone = false;
        viewModel.resetState();
        SensorCoordinator.getInstance().activateFingerprint();
        viewModel.scanFingerprint();
    }

    private void goBackToHome() {
        viewModel.cancelScan();
        requireActivity().getSupportFragmentManager().popBackStack();
    }

    private int getColor(int resId) {
        return requireContext().getColor(resId);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
