package com.petronova.kiosk.ui.scan;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.petronova.kiosk.config.AppConfig;
import com.petronova.kiosk.data.model.Worker;
import com.petronova.kiosk.data.repo.WorkerRepository;
import com.petronova.kiosk.service.FingerprintService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * ViewModel de la pantalla de escaneo.
 * Equivalente de las funciones de scanning.js:
 *   startFingerprintScan(), startNFCScan(), showWorkerData(), checkFuelAssignment().
 */
public class ScanViewModel extends ViewModel {

    public enum ScanState {
        IDLE, SCANNING_FINGERPRINT, SCANNING_NFC, SCANNING_QR,
        SUCCESS, ERROR, CANCELLED
    }

    private static final int CI_DIGITS = 11;

    private final MutableLiveData<ScanState>  state         = new MutableLiveData<>(ScanState.IDLE);
    private final MutableLiveData<Worker>     detectedWorker = new MutableLiveData<>();
    private final MutableLiveData<String>     errorMessage  = new MutableLiveData<>();
    private final MutableLiveData<String>     fingerprintPreview = new MutableLiveData<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?>             currentScan;

    // ─── Escaneo de huella (equivalente startFingerprintScan() en scanning.js:80) ─

    public void scanFingerprint() {
        if (executor.isShutdown()) return;
        if (state.getValue() == ScanState.SCANNING_FINGERPRINT) return;
        state.setValue(ScanState.SCANNING_FINGERPRINT);
        fingerprintPreview.setValue("");
        submitScanTask();
    }

    private void submitScanTask() {
        currentScan = executor.submit(() -> {
            FingerprintService.ScanResult result =
                FingerprintService.getInstance().scanFingerprint(AppConfig.SCAN_TIMEOUT_SECONDS);

            if (!result.previewBase64.isEmpty()) {
                fingerprintPreview.postValue(result.previewBase64);
            }
            if (result.success) {
                detectedWorker.postValue(result.worker);
                state.postValue(ScanState.SUCCESS);
            } else if (result.error != null && result.error.contains("timeout o sin hardware")) {
                // Timeout del sensor: reiniciar escaneo silenciosamente sin mostrar error
                if (!executor.isShutdown()) submitScanTask();
            } else {
                errorMessage.postValue(result.error);
                state.postValue(ScanState.ERROR);
            }
        });
    }

    /** Cancela el escaneo en curso (equivalente cancelScanningAndGoBack en scanning.js). */
    public void cancelScan() {
        FingerprintService.getInstance().cancel();
        if (currentScan != null) currentScan.cancel(true);
        state.setValue(ScanState.CANCELLED);
    }

    // ─── Escaneo NFC (resultado llega por callback desde AndroidNfcController) ─

    public void onNfcTagDetected(String uid) {
        state.setValue(ScanState.SCANNING_NFC);
        // Buscar el trabajador en BD por UID
        WorkerRepository.getInstance().findByNfc(uid).observeForever(result -> {
            if (result.success) {
                detectedWorker.postValue(result.data);
                state.postValue(ScanState.SUCCESS);
            } else {
                errorMessage.postValue(result.error);
                state.postValue(ScanState.ERROR);
            }
        });
    }

    // ─── Escaneo QR (botón físico QS805 → broadcast com.qs.scancode) ─────────

    public void onQrScanned(@Nullable String data) {
        String ci = extractCi(data);
        if (ci == null) {
            errorMessage.postValue("QR no contiene CI válido");
            state.postValue(ScanState.ERROR);
            return;
        }
        state.setValue(ScanState.SCANNING_QR);
        WorkerRepository.getInstance().findByCi(ci).observeForever(result -> {
            if (result.success) {
                detectedWorker.postValue(result.data);
                state.postValue(ScanState.SUCCESS);
            } else {
                errorMessage.postValue(result.error);
                state.postValue(ScanState.ERROR);
            }
        });
    }

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
                break;
            }
        }
        return sb.length() == CI_DIGITS ? sb.toString() : null;
    }

    // ─── Observables ────────────────────────────────────────────────────────

    public LiveData<ScanState> getState()              { return state; }
    public LiveData<Worker>    getDetectedWorker()      { return detectedWorker; }
    public LiveData<String>    getErrorMessage()        { return errorMessage; }
    public LiveData<String>    getFingerprintPreview()  { return fingerprintPreview; }

    public void resetState() {
        state.setValue(ScanState.IDLE);
        detectedWorker.setValue(null);
        errorMessage.setValue(null);
        fingerprintPreview.setValue("");
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        cancelScan();
        executor.shutdown();
    }
}
