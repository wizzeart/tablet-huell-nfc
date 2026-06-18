package com.petronova.kiosk.ui.registration;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.petronova.kiosk.data.model.Worker;
import com.petronova.kiosk.data.repo.WorkerRepository;
import com.petronova.kiosk.service.FingerprintService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ViewModel de registro biométrico.
 * Equivalente de las funciones de registration.js:
 *   searchWorkerForFingerprint(), startFingerprintCapture(),
 *   searchWorkerForNFC(), startNFCCapture().
 */
public class RegistrationViewModel extends ViewModel {

    public enum RegistrationState { IDLE, CAPTURING, SUCCESS, ERROR }

    private final MutableLiveData<Worker>            foundWorker        = new MutableLiveData<>();
    private final MutableLiveData<RegistrationState> registrationState  = new MutableLiveData<>(RegistrationState.IDLE);
    private final MutableLiveData<String>            error              = new MutableLiveData<>();
    /** Evento one-shot: CI buscado pero no encontrado. El observador lo consume con clearSearchNotFound(). */
    private final MutableLiveData<String>            searchNotFound     = new MutableLiveData<>();
    /** Preview (data-URI base64) de la huella capturada durante el registro. */
    private final MutableLiveData<String>            enrollPreview      = new MutableLiveData<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final WorkerRepository repo    = WorkerRepository.getInstance();

    // ─── Buscar trabajador por CI (equivalente searchWorkerForFingerprint/searchWorkerForNFC) ─

    public void searchWorker(String ci) {
        repo.findByCi(ci).observeForever(result -> {
            if (result.success) {
                foundWorker.postValue(result.data);
                error.postValue(null);
            } else {
                foundWorker.postValue(null);
                error.postValue(result.error);
                // Dispara el toast + voz "Trabajador no encontrado" en el Fragment.
                searchNotFound.postValue(result.error);
            }
        });
    }

    /** Limpia el evento tras consumirlo para que no se reproduzca de nuevo. */
    public void clearSearchNotFound() {
        searchNotFound.setValue(null);
    }

    // ─── Registrar huella (equivalente startFingerprintCapture en registration.js) ─

    public void enrollFingerprint(int timeoutSeconds) {
        if (executor.isShutdown()) return;
        Worker worker = foundWorker.getValue();
        if (worker == null) return;

        registrationState.setValue(RegistrationState.CAPTURING);
        final int workerId = worker.id;

        executor.execute(() -> {
            FingerprintService.EnrollResult result =
                FingerprintService.getInstance().enrollFingerprint(workerId, timeoutSeconds);

            if (result.previewBase64 != null && !result.previewBase64.isEmpty()) {
                enrollPreview.postValue(result.previewBase64);
            }
            if (result.success) {
                registrationState.postValue(RegistrationState.SUCCESS);
                error.postValue(null);
            } else {
                registrationState.postValue(RegistrationState.ERROR);
                error.postValue(result.error);
            }
        });
    }

    // ─── Registrar NFC (equivalente startNFCCapture en registration.js) ──────

    public void enrollNfc(String nfcUid) {
        Worker worker = foundWorker.getValue();
        if (worker == null) return;

        registrationState.postValue(RegistrationState.CAPTURING);
        repo.saveNfc(worker.id, nfcUid).observeForever(result -> {
            if (result.success) {
                registrationState.postValue(RegistrationState.SUCCESS);
                error.postValue(null);
            } else {
                registrationState.postValue(RegistrationState.ERROR);
                error.postValue(result.error);
            }
        });
    }

    // ─── Observables ────────────────────────────────────────────────────────

    public LiveData<Worker>            getFoundWorker()       { return foundWorker; }
    public LiveData<RegistrationState> getRegistrationState() { return registrationState; }
    public LiveData<String>            getError()             { return error; }
    public LiveData<String>            getSearchNotFound()    { return searchNotFound; }
    public LiveData<String>            getEnrollPreview()     { return enrollPreview; }

    @Override
    protected void onCleared() {
        super.onCleared();
        executor.shutdown();
    }
}
