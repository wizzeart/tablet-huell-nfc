package com.petronova.kiosk.data.repo;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.petronova.kiosk.data.db.WorkerDao;
import com.petronova.kiosk.data.model.Worker;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repositorio de trabajadores. Ejecuta operaciones JDBC en hilo de fondo y
 * expone resultados como LiveData para que los ViewModels los observen.
 */
public class WorkerRepository {

    private static WorkerRepository instance;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** Cache en memoria de todos los workers con huella — equivalente a _workers_cache en secugen_service.py. */
    private List<Worker> workersCache = null;

    public static WorkerRepository getInstance() {
        if (instance == null) instance = new WorkerRepository();
        return instance;
    }

    // ─── findByCi ────────────────────────────────────────────────────────────

    public LiveData<Result<Worker>> findByCi(String ci) {
        MutableLiveData<Result<Worker>> liveData = new MutableLiveData<>();
        executor.execute(() -> {
            try {
                Worker w = WorkerDao.findByCi(ci);
                liveData.postValue(w != null ? Result.success(w) : Result.error("Trabajador no encontrado"));
            } catch (SQLException e) {
                liveData.postValue(Result.error(e.getMessage()));
            }
        });
        return liveData;
    }

    // ─── findByNfc ───────────────────────────────────────────────────────────

    public LiveData<Result<Worker>> findByNfc(String uid) {
        MutableLiveData<Result<Worker>> liveData = new MutableLiveData<>();
        executor.execute(() -> {
            try {
                Worker w = WorkerDao.findByNfc(uid);
                liveData.postValue(w != null ? Result.success(w) : Result.error("Tarjeta NFC no registrada"));
            } catch (SQLException e) {
                liveData.postValue(Result.error(e.getMessage()));
            }
        });
        return liveData;
    }

    // ─── Cache de workers con huella (para matching) ─────────────────────────

    /** Recarga la cache desde BD y la devuelve. Bloqueante — llamar desde hilo de fondo. */
    public List<Worker> loadWorkersWithHuella() throws SQLException {
        workersCache = WorkerDao.getWorkersWithHuella();
        return workersCache;
    }

    /** Devuelve la cache actual (puede estar vacía si no se ha cargado). */
    public List<Worker> getCachedWorkers() {
        return workersCache;
    }

    // ─── Guardar huella ──────────────────────────────────────────────────────

    public LiveData<Result<Boolean>> saveHuella(int workerId, String huellaHex) {
        MutableLiveData<Result<Boolean>> liveData = new MutableLiveData<>();
        executor.execute(() -> {
            try {
                boolean ok = WorkerDao.clearHuella(workerId) | WorkerDao.saveHuella(workerId, huellaHex);
                if (ok) {
                    workersCache = WorkerDao.getWorkersWithHuella(); // recargar cache
                    liveData.postValue(Result.success(true));
                } else {
                    liveData.postValue(Result.error("No se pudo guardar la huella"));
                }
            } catch (SQLException e) {
                liveData.postValue(Result.error(e.getMessage()));
            }
        });
        return liveData;
    }

    // ─── Guardar NFC ─────────────────────────────────────────────────────────

    public LiveData<Result<Boolean>> saveNfc(int workerId, String nfcUid) {
        MutableLiveData<Result<Boolean>> liveData = new MutableLiveData<>();
        executor.execute(() -> {
            try {
                boolean ok = WorkerDao.saveNfc(workerId, nfcUid);
                liveData.postValue(ok ? Result.success(true) : Result.error("No se pudo guardar NFC"));
            } catch (SQLException e) {
                liveData.postValue(Result.error(e.getMessage()));
            }
        });
        return liveData;
    }

    // ─── Result wrapper ──────────────────────────────────────────────────────

    public static class Result<T> {
        public final T      data;
        public final String error;
        public final boolean success;

        private Result(T data, String error, boolean success) {
            this.data    = data;
            this.error   = error;
            this.success = success;
        }

        public static <T> Result<T> success(T data) { return new Result<>(data, null, true); }
        public static <T> Result<T> error(String msg) { return new Result<>(null, msg, false); }
    }
}
