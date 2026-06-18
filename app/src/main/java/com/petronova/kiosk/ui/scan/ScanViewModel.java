package com.petronova.kiosk.ui.scan;

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
        IDLE, SCANNING_FINGERPRINT, SCANNING_NFC,
        SUCCESS, ERROR, CANCELLED
    }

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

        currentScan = executor.submit(() -> {
            FingerprintService.ScanResult result =
                FingerprintService.getInstance().scanFingerprint(AppConfig.SCAN_TIMEOUT_SECONDS);

            if (!result.previewBase64.isEmpty()) {
                fingerprintPreview.postValue(result.previewBase64);
            }
            if (result.success) {
                detectedWorker.postValue(result.worker);
                state.postValue(ScanState.SUCCESS);
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
