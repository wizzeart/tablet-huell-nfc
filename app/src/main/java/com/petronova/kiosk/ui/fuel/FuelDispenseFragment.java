package com.petronova.kiosk.ui.fuel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.petronova.kiosk.R;
import com.petronova.kiosk.audio.TtsManager;
import com.petronova.kiosk.config.AppConfig;
import com.petronova.kiosk.data.local.LocalConfigStore;
import com.petronova.kiosk.data.repo.WorkerRepository;
import com.petronova.kiosk.databinding.FragmentFuelDispenseBinding;
import com.petronova.kiosk.network.PetronovaApiClient;
import com.petronova.kiosk.sensors.SensorCoordinator;
import com.petronova.kiosk.service.FingerprintService;
import com.petronova.kiosk.ui.main.MainActivity;
import com.petronova.kiosk.util.ToastSpeaker;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Vista nativa de asignación/retiro de combustible, portada del modal fuelModal del proyecto web. */
public class FuelDispenseFragment extends Fragment {

    private static final String ARG_WORKER_ID = "worker_id";
    private static final String ARG_WORKER_NAME = "worker_name";
    private static final String ARG_WORKER_CI = "worker_ci";

    /** Acción del broadcast del escáner QR físico (hardware QS805) — igual que en ScanFragment. */
    private static final String ACTION_SCAN_RESULT = "com.qs.scancode";
    private static final int CI_DIGITS = 11;

    private FragmentFuelDispenseBinding binding;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<FuelAssignment> assignments = new ArrayList<>();
    private final List<AssignedVehicle> vehicles = new ArrayList<>();
    private FuelAssignment selected;
    private AssignedVehicle selectedVehicle;
    private boolean bidonAuthorized;
    private int workerId;
    private String workerName;
    private String workerCi;
    private double pendingLiters;
    private ConfirmStep confirmStep = ConfirmStep.NONE;
    private Future<?> fingerprintScanTask;
    private android.view.animation.Animation confirmPulse;
    /** Evita doble registro cuando dos métodos (huella/NFC/QR) confirman casi a la vez. */
    private boolean confirmHandled;
    private BroadcastReceiver qrConfirmReceiver;

    private enum ConfirmStep { NONE, INVOICE, CONFIRMING }

    public static FuelDispenseFragment newInstance(int workerId, String workerName, String workerCi) {
        FuelDispenseFragment f = new FuelDispenseFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_WORKER_ID, workerId);
        args.putString(ARG_WORKER_NAME, workerName);
        args.putString(ARG_WORKER_CI, workerCi);
        f.setArguments(args);
        return f;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentFuelDispenseBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Bundle args = requireArguments();
        workerId = args.getInt(ARG_WORKER_ID);
        workerName = args.getString(ARG_WORKER_NAME, "");
        workerCi = args.getString(ARG_WORKER_CI, "");

        binding.tvFuelWorker.setText(getString(R.string.fuel_worker) + ": " + workerName + "  CI: " + workerCi);
        binding.btnFuelBack.setOnClickListener(v -> handleConfirmBack());
        binding.btnFuelDispense.setOnClickListener(v -> confirmRetiro());
        binding.btnFuelConfirmProceed.setOnClickListener(v -> startConfirmation());
        binding.btnFuelConfirmCancel.setOnClickListener(v -> handleConfirmBack());
        setBusy(true, getString(R.string.fuel_loading));
        loadAssignments();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (confirmStep == ConfirmStep.CONFIRMING) {
            cancelFingerprintScan();
            stopConfirmListeners();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (confirmStep == ConfirmStep.CONFIRMING) {
            startConfirmListeners();
            scanFingerprintForConfirmation();
        }
    }

    private void handleConfirmBack() {
        if (confirmStep == ConfirmStep.CONFIRMING) {
            showWithdrawalInvoice();
            return;
        }
        if (confirmStep == ConfirmStep.INVOICE) {
            cancelConfirmationFlow();
            return;
        }
        requireActivity().getSupportFragmentManager().popBackStack();
    }

    private void loadAssignments() {
        executor.execute(() -> {
            PetronovaApiClient.ApiResult result = PetronovaApiClient.getInstance().getAsignaciones(workerId);
            if (!isAdded() || getContext() == null || getActivity() == null) return;

            if (!result.success) {
                postStatus(getString(R.string.fuel_assignment_error) + ": " + result.error, true);
                return;
            }

            List<FuelAssignment> loaded = parseAssignments(result.data);
            getActivity().runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                extractVehiclesAndBidon(result.data, loaded);
                assignments.clear();
                assignments.addAll(loaded);
                renderAssignments();
                renderVehicles();
                updateBidonUI();
                if (assignments.isEmpty()) {
                    setBusy(false, getString(R.string.fuel_no_assignment));
                    ToastSpeaker.show(requireContext(), getString(R.string.fuel_no_assignment));
                } else {
                    selectAssignment(assignments.get(0));
                    setBusy(false, getString(R.string.fuel_select_assignment));
                }
            });
        });
    }

    private void extractVehiclesAndBidon(JsonElement data, List<FuelAssignment> loaded) {
        vehicles.clear();
        selectedVehicle = null;
        bidonAuthorized = false;
        for (FuelAssignment assignment : loaded) {
            if (assignment.bidonAuthorized) bidonAuthorized = true;
        }
        JsonArray array = extractArray(data);
        for (JsonElement el : array) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject obj = el.getAsJsonObject();
            if (vehicles.isEmpty() && obj.has("vehiculo_id")) {
                vehicles.addAll(AssignedVehicle.parseList(obj.get("vehiculo_id")));
            }
        }
    }

    private void renderVehicles() {
        if (binding == null) return;
        binding.llFuelVehicles.removeAllViews();
        if (vehicles.isEmpty()) {
            binding.tvFuelNoVehicles.setVisibility(View.VISIBLE);
            return;
        }
        binding.tvFuelNoVehicles.setVisibility(View.GONE);

        RadioGroup group = new RadioGroup(requireContext());
        group.setOrientation(RadioGroup.VERTICAL);
        for (AssignedVehicle vehicle : vehicles) {
            RadioButton rb = new RadioButton(requireContext());
            rb.setText(vehicle.name + "\n" + getString(R.string.fuel_vehicle_fuel_type, vehicle.fuelTypeName));
            rb.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_text_primary));
            rb.setTag(vehicle);
            rb.setPadding(0, 8, 0, 8);
            group.addView(rb, new LinearLayoutParams());
        }
        group.setOnCheckedChangeListener((g, checkedId) -> {
            if (checkedId == View.NO_ID) return;
            RadioButton rb = g.findViewById(checkedId);
            if (rb != null && rb.getTag() instanceof AssignedVehicle) {
                onVehicleSelected((AssignedVehicle) rb.getTag());
            }
        });
        binding.llFuelVehicles.addView(group, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void updateBidonUI() {
        if (binding == null) return;
        if (bidonAuthorized) {
            binding.tvFuelBidonStatus.setText(R.string.fuel_bidon_authorized);
            binding.tvFuelBidonStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_success));
        } else {
            binding.tvFuelBidonStatus.setText(R.string.fuel_bidon_not_authorized);
            binding.tvFuelBidonStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.color_error));
        }
    }

    private void onVehicleSelected(AssignedVehicle vehicle) {
        if (binding == null || vehicle == null) return;
        selectedVehicle = vehicle;
        FuelAssignment matching = findAssignmentForVehicle(vehicle);
        if (matching != null) {
            selectAssignment(matching, true);
            appendLog("Vehículo seleccionado: " + vehicle.name);
        } else {
            updateStatusDisplay(getString(R.string.fuel_select_assignment));
            appendLog("Sin asignación para combustible del vehículo: " + vehicle.fuelTypeName);
        }
    }

    private FuelAssignment findAssignmentForVehicle(AssignedVehicle vehicle) {
        for (FuelAssignment assignment : assignments) {
            if (vehicle.fuelTypeId > 0 && assignment.fuelTypeId == vehicle.fuelTypeId) {
                return assignment;
            }
            if (!TextUtils.isEmpty(vehicle.fuelTypeName)
                    && !TextUtils.isEmpty(assignment.fuelType)
                    && vehicle.fuelTypeName.trim().equalsIgnoreCase(assignment.fuelType.trim())) {
                return assignment;
            }
        }
        return null;
    }

    private void clearVehicleSelection() {
        selectedVehicle = null;
        if (binding == null) return;
        for (int i = 0; i < binding.llFuelVehicles.getChildCount(); i++) {
            View child = binding.llFuelVehicles.getChildAt(i);
            if (child instanceof RadioGroup) {
                ((RadioGroup) child).clearCheck();
            }
        }
    }

    private void renderAssignments() {
        if (binding == null) return;

        // Solo los usuarios con bidón autorizado eligen el tipo de combustible a mano (lo
        // retiran en un recipiente externo). Si NO tiene bidón, el combustible queda
        // determinado por el vehículo que seleccione, así que ocultamos esta selección.
        int assignmentVisibility = bidonAuthorized ? View.VISIBLE : View.GONE;
        binding.tvFuelAssignmentsLabel.setVisibility(assignmentVisibility);
        binding.llFuelAssignments.setVisibility(assignmentVisibility);

        binding.llFuelAssignments.removeAllViews();
        if (!bidonAuthorized) return;

        for (FuelAssignment assignment : assignments) {
            Button btn = new Button(requireContext());
            btn.setText(assignment.fuelType + "  " + formatLiters(assignment.qty) + " L");
            btn.setAllCaps(false);
            btn.setOnClickListener(v -> selectAssignment(assignment));
            binding.llFuelAssignments.addView(btn, new LinearLayoutParams());
        }
    }

    private void selectAssignment(FuelAssignment assignment) {
        selectAssignment(assignment, false);
    }

    private void selectAssignment(FuelAssignment assignment, boolean fromVehicle) {
        if (binding == null) return;
        selected = assignment;
        if (!fromVehicle) clearVehicleSelection();
        binding.tvFuelType.setText(assignment.fuelType);
        binding.tvFuelQty.setText(formatLiters(assignment.qty) + " L");
        updateStatusDisplay(getString(R.string.fuel_select_assignment));
        appendLog("Asignación seleccionada: " + assignment.fuelType + " / " + formatLiters(assignment.qty) + " L");
    }

    private void confirmRetiro() {
        if (selected == null) {
            ToastSpeaker.show(requireContext(), getString(R.string.fuel_no_assignment));
            return;
        }
        String raw = binding.etFuelLiters.getText() != null
                ? binding.etFuelLiters.getText().toString().replace(",", ".").trim()
                : "";
        double liters;
        try {
            liters = Double.parseDouble(raw);
        } catch (Exception e) {
            ToastSpeaker.show(requireContext(), getString(R.string.fuel_invalid_amount));
            return;
        }
        if (liters <= 0) {
            ToastSpeaker.show(requireContext(), getString(R.string.fuel_invalid_amount));
            return;
        }
        if (liters > selected.qty) {
            ToastSpeaker.show(requireContext(), getString(R.string.fuel_amount_exceeds));
            return;
        }
        if (!hasActivePistero()) {
            String msg = getString(R.string.fuel_pistero_required);
            updateStatusDisplay(msg);
            ToastSpeaker.show(requireContext(), msg);
            TtsManager.getInstance().speak(msg);
            return;
        }
        if (selectedVehicle == null && !bidonAuthorized) {
            String msg = getString(R.string.fuel_select_vehicle);
            updateStatusDisplay(msg);
            ToastSpeaker.show(requireContext(), msg);
            TtsManager.getInstance().speak(msg);
            return;
        }

        pendingLiters = liters;
        showWithdrawalInvoice();
    }

    private boolean hasActivePistero() {
        LocalConfigStore.PisteroData pistero = new LocalConfigStore(requireContext()).getPistero();
        return pistero != null && pistero.userId > 0;
    }

    private void showWithdrawalInvoice() {
        if (binding == null || selected == null) return;

        cancelFingerprintScan();
        stopConfirmListeners();
        SensorCoordinator.getInstance().deactivateAll();
        confirmStep = ConfirmStep.INVOICE;

        binding.fuelContent.setVisibility(View.GONE);
        binding.llFuelConfirmPanel.setVisibility(View.VISIBLE);
        binding.overlayFuelFingerprint.setVisibility(View.GONE);
        binding.llFuelConfirmActions.setVisibility(View.VISIBLE);
        binding.ivFuelConfirmIcon.clearAnimation();
        binding.tvFuelConfirmStatus.setText("");
        binding.ivFuelConfirmPreview.setVisibility(View.GONE);
        binding.ivFuelConfirmPreview.setImageDrawable(null);

        populateInvoice();
    }

    private void populateInvoice() {
        if (binding == null || selected == null) return;

        binding.tvInvoiceWorker.setText(workerName);
        binding.tvInvoiceCi.setText("CI: " + workerCi);
        binding.tvInvoiceFuel.setText(selected.fuelType);
        binding.tvInvoiceVehicle.setText(selectedVehicle != null
                ? selectedVehicle.name
                : getString(R.string.fuel_invoice_vehicle_bidon));
        binding.tvInvoiceBidon.setText(bidonAuthorized
                ? getString(R.string.fuel_bidon_authorized)
                : getString(R.string.fuel_bidon_not_authorized));
        binding.tvInvoiceBidon.setTextColor(ContextCompat.getColor(requireContext(),
                bidonAuthorized ? R.color.color_success : R.color.color_error));
        binding.tvInvoiceWithdraw.setText(getString(R.string.fuel_invoice_liters_value, formatLiters(pendingLiters)));
    }

    private void startConfirmation() {
        if (binding == null || confirmStep != ConfirmStep.INVOICE) return;

        confirmStep = ConfirmStep.CONFIRMING;
        confirmHandled = false;
        binding.overlayFuelFingerprint.setVisibility(View.VISIBLE);
        binding.tvFuelConfirmInstruction.setText(
                getString(R.string.fuel_confirm_fingerprint, formatLiters(pendingLiters)));
        binding.tvFuelConfirmStatus.setText(getString(R.string.fuel_confirm_waiting));
        binding.ivFuelConfirmPreview.setVisibility(View.GONE);
        binding.ivFuelConfirmPreview.setImageDrawable(null);

        if (confirmPulse == null) {
            confirmPulse = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(), R.anim.neon_pulse);
        }
        binding.ivFuelConfirmIcon.startAnimation(confirmPulse);

        // Confirmación por los 3 métodos a la vez: huella, NFC y QR.
        startConfirmListeners();
        scanFingerprintForConfirmation();
    }

    // ─── Listeners NFC + QR para la confirmación ───────────────────────────────

    /** Activa la lectura NFC y el receptor del escáner QR mientras dura la confirmación. */
    private void startConfirmListeners() {
        if (getActivity() == null) return;
        SensorCoordinator.getInstance().activateNfc();
        ((MainActivity) requireActivity()).getNfcController().startReading(this::onConfirmNfc);

        if (qrConfirmReceiver == null) {
            qrConfirmReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || intent.getExtras() == null) return;
                    onConfirmQr(intent.getExtras().getString("data"));
                }
            };
            ContextCompat.registerReceiver(requireContext(), qrConfirmReceiver,
                    new IntentFilter(ACTION_SCAN_RESULT), ContextCompat.RECEIVER_EXPORTED);
        }
    }

    /** Detiene la lectura NFC y desregistra el receptor QR. Idempotente. */
    private void stopConfirmListeners() {
        if (getActivity() != null) {
            ((MainActivity) requireActivity()).getNfcController().stopReading();
        }
        if (qrConfirmReceiver != null) {
            try { requireContext().unregisterReceiver(qrConfirmReceiver); } catch (Exception ignore) {}
            qrConfirmReceiver = null;
        }
    }

    /** Tarjeta NFC detectada durante la confirmación: válida si pertenece al usuario identificado. */
    private void onConfirmNfc(String uid) {
        if (confirmStep != ConfirmStep.CONFIRMING || confirmHandled || !isAdded()) return;
        WorkerRepository.getInstance().findByNfc(uid).observe(getViewLifecycleOwner(), result -> {
            if (confirmStep != ConfirmStep.CONFIRMING || confirmHandled) return;
            if (result.success && result.data != null && result.data.id == workerId) {
                onConfirmSuccess("NFC");
            } else {
                showConfirmMismatch();
            }
        });
    }

    /** QR leído durante la confirmación: válido si el CI coincide con el usuario identificado. */
    private void onConfirmQr(String data) {
        if (confirmStep != ConfirmStep.CONFIRMING || confirmHandled || !isAdded()) return;
        String ci = extractCi(data);
        if (ci == null) {
            showConfirmMismatch();
            return;
        }
        WorkerRepository.getInstance().findByCi(ci).observe(getViewLifecycleOwner(), result -> {
            if (confirmStep != ConfirmStep.CONFIRMING || confirmHandled) return;
            if (result.success && result.data != null && result.data.id == workerId) {
                onConfirmSuccess("QR");
            } else {
                showConfirmMismatch();
            }
        });
    }

    /** Confirmación exitosa por cualquier método: registra el consumo una sola vez. */
    private void onConfirmSuccess(String method) {
        if (confirmHandled || !isAdded()) return;
        confirmHandled = true;
        appendLog("Retiro confirmado por " + method + " para " + workerName);
        cancelFingerprintScan();
        stopConfirmListeners();
        hideConfirmationScreen();
        setBusy(true, getString(R.string.fuel_registering));
        executor.execute(() -> registerConsumption(pendingLiters));
    }

    /** Muestra y anuncia que la identificación no coincide, sin abortar la confirmación. */
    private void showConfirmMismatch() {
        if (binding == null || confirmStep != ConfirmStep.CONFIRMING || confirmHandled) return;
        String msg = getString(R.string.fuel_confirm_mismatch);
        binding.tvFuelConfirmStatus.setText(msg);
        ToastSpeaker.show(requireContext(), msg);
        TtsManager.getInstance().speak(msg);
    }

    private void scanFingerprintForConfirmation() {
        if (confirmStep != ConfirmStep.CONFIRMING || binding == null || !isAdded()) return;

        cancelFingerprintScan();
        fingerprintScanTask = executor.submit(() -> {
            FingerprintService.ScanResult result = FingerprintService.getInstance()
                    .scanFingerprint(AppConfig.SCAN_TIMEOUT_SECONDS);

            if (!isAdded() || getActivity() == null
                    || confirmStep != ConfirmStep.CONFIRMING || confirmHandled) return;

            getActivity().runOnUiThread(() -> {
                if (!isAdded() || binding == null
                        || confirmStep != ConfirmStep.CONFIRMING || confirmHandled) return;

                if (!result.previewBase64.isEmpty()) {
                    android.graphics.Bitmap bmp =
                            com.petronova.kiosk.util.ImageUtils.decodeDataUri(result.previewBase64);
                    if (bmp != null) {
                        binding.ivFuelConfirmPreview.setImageBitmap(bmp);
                        com.petronova.kiosk.util.ImageUtils.applyNeonFingerprint(binding.ivFuelConfirmPreview);
                        binding.ivFuelConfirmPreview.setVisibility(View.VISIBLE);
                    }
                }

                if (result.success && result.worker != null && result.worker.id == workerId) {
                    onConfirmSuccess("huella");
                    return;
                }

                if (result.success && result.worker != null) {
                    // Huella reconocida pero de otro usuario.
                    showConfirmMismatch();
                } else if (result.error != null && !result.error.contains("timeout o sin hardware")) {
                    // Error real del sensor (no el timeout silencioso de re-captura).
                    binding.tvFuelConfirmStatus.setText(result.error);
                }

                // El lector de huella se reintenta solo; NFC y QR siguen escuchando en paralelo.
                binding.getRoot().postDelayed(() -> {
                    if (confirmStep == ConfirmStep.CONFIRMING && !confirmHandled && isAdded()) {
                        scanFingerprintForConfirmation();
                    }
                }, AppConfig.MODAL_AUTO_CLOSE_MS);
            });
        });
    }

    private void cancelConfirmationFlow() {
        cancelFingerprintScan();
        stopConfirmListeners();
        SensorCoordinator.getInstance().deactivateAll();
        hideConfirmationScreen();
        setBusy(false, getString(R.string.fuel_select_assignment));
    }

    private void hideConfirmationScreen() {
        confirmStep = ConfirmStep.NONE;
        stopConfirmListeners();
        if (binding == null) return;
        binding.ivFuelConfirmIcon.clearAnimation();
        binding.overlayFuelFingerprint.setVisibility(View.GONE);
        binding.llFuelConfirmPanel.setVisibility(View.GONE);
        binding.fuelContent.setVisibility(View.VISIBLE);
        SensorCoordinator.getInstance().deactivateAll();
    }

    /** Extrae el CI (11 dígitos) que sigue a "CI:" en el texto del QR. Igual que ScanViewModel. */
    @Nullable
    private static String extractCi(@Nullable String data) {
        if (data == null) return null;
        int idx = data.toUpperCase(Locale.US).indexOf("CI:");
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

    private void cancelFingerprintScan() {
        if (fingerprintScanTask != null) {
            FingerprintService.getInstance().cancel();
            fingerprintScanTask.cancel(true);
            fingerprintScanTask = null;
        }
    }

    @Override
    public void onDestroyView() {
        cancelFingerprintScan();
        stopConfirmListeners();
        confirmStep = ConfirmStep.NONE;
        if (binding != null) binding.ivFuelConfirmIcon.clearAnimation();
        confirmPulse = null;
        super.onDestroyView();
        binding = null;
    }

    private void registerConsumption(double liters) {
        LocalConfigStore store = new LocalConfigStore(requireContext());
        LocalConfigStore.PisteroData pistero = store.getPistero();
        if (pistero == null || pistero.userId <= 0) {
            postStatus(getString(R.string.fuel_pistero_required), true);
            return;
        }

        int tankId = resolveTankId(store, selected.fuelTypeId);
        Map<String, Object> consumoPayload = new HashMap<>();
        consumoPayload.put("cantidad_consumida", liters);
        consumoPayload.put("user_id", selected.userId > 0 ? selected.userId : workerId);
        consumoPayload.put("vehiculo_id", selectedVehicle != null ? selectedVehicle.id : null);
        consumoPayload.put("tanque_id", tankId);
        consumoPayload.put("id_tipocombustible", selected.fuelTypeId);
        consumoPayload.put("realizado_por_id", pistero.userId);

        appendLog("Registrando consumo: " + formatLiters(liters) + " L / tanque " + tankId);
        PetronovaApiClient.ApiResult consumo = PetronovaApiClient.getInstance().registrarConsumo(consumoPayload);
        
        if (!isAdded() || getContext() == null || getActivity() == null) return;

        if (!consumo.success) {
            postStatus("Error registrando consumo: " + consumo.error, true);
            return;
        }

        double newQty = Math.max(0, selected.qty - liters);
        if (selected.id > 0) {
            Map<String, Object> updatePayload = new HashMap<>();
            updatePayload.put("cantidad_asignada", newQty);
            updatePayload.put("user_id", selected.userId > 0 ? selected.userId : workerId);
            updatePayload.put("id_tipocombustible", selected.fuelTypeId);
            PetronovaApiClient.ApiResult update = PetronovaApiClient.getInstance()
                    .updateAsignacion(selected.id, updatePayload);
            if (!update.success) {
                appendLog("Advertencia: no se pudo actualizar asignación: " + update.error);
            }
        } else {
            appendLog("Advertencia: la asignación no trajo id; se omite actualización de saldo.");
        }

        selected.qty = newQty;
        getActivity().runOnUiThread(() -> {
            if (!isAdded() || binding == null) return;
            binding.tvFuelQty.setText(formatLiters(newQty) + " L");
            binding.etFuelLiters.setText("");
            String successMsg = getString(R.string.fuel_consumption_ok);
            String thanksMsg = getString(R.string.fuel_consumption_thanks);
            setBusy(false, successMsg + "\n" + thanksMsg);
            ToastSpeaker.show(requireContext(), successMsg + " " + thanksMsg);
            TtsManager.getInstance().speak(successMsg);
            appendLog(getString(R.string.fuel_hardware_pending));
            renderAssignments();

            binding.getRoot().postDelayed(() -> {
                if (isAdded()) TtsManager.getInstance().speak(thanksMsg);
            }, 2200L);

            // Tras dispensar/retirar combustible: volver al inicio cuando termine el mensaje.
            binding.getRoot().postDelayed(() -> {
                if (!isAdded() || getActivity() == null) return;
                ((MainActivity) requireActivity()).goToMainScreen();
            }, 5000L);
        });
    }

    private void postStatus(String message, boolean toast) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (!isAdded() || binding == null) return;
            setBusy(false, message);
            appendLog(message);
            if (toast) ToastSpeaker.show(requireContext(), message);
        });
    }

    private void setBusy(boolean busy, String status) {
        if (binding == null) return;
        binding.btnFuelDispense.setEnabled(!busy);
        updateStatusDisplay(status);
    }

    private void updateStatusDisplay(String status) {
        if (binding == null) return;
        String selectAssignment = getString(R.string.fuel_select_assignment);
        if (status == null || selectAssignment.contentEquals(status)) {
            binding.tvFuelStatus.setVisibility(View.GONE);
            return;
        }
        binding.tvFuelStatus.setVisibility(View.VISIBLE);
        binding.tvFuelStatus.setText(status);
    }

    private void appendLog(String line) {
        if (binding == null || line == null || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (binding == null) return;
            String current = binding.tvFuelLog.getText() != null ? binding.tvFuelLog.getText().toString() : "";
            binding.tvFuelLog.setText(current + "\n" + line);
        });
    }

    private int resolveTankId(LocalConfigStore store, int fuelTypeId) {
        String selectedTank = store.getTankSelections().get(String.valueOf(fuelTypeId));
        if (!TextUtils.isEmpty(selectedTank)) {
            try { return Integer.parseInt(selectedTank); } catch (Exception ignored) {}
        }
        return 5;
    }

    private List<FuelAssignment> parseAssignments(JsonElement data) {
        List<FuelAssignment> out = new ArrayList<>();
        JsonArray array = extractArray(data);
        for (JsonElement el : array) {
            if (el != null && el.isJsonObject()) {
                FuelAssignment assignment = FuelAssignment.fromJson(el.getAsJsonObject(), workerId);
                if (assignment != null && assignment.fuelTypeId > 0) out.add(assignment);
            }
        }
        return out;
    }

    private JsonArray extractArray(JsonElement data) {
        if (data == null || data.isJsonNull()) return new JsonArray();
        if (data.isJsonArray()) return data.getAsJsonArray();
        if (!data.isJsonObject()) return new JsonArray();
        JsonObject obj = data.getAsJsonObject();
        JsonElement nested = obj.get("data");
        if (nested != null && nested.isJsonArray()) return nested.getAsJsonArray();
        if (nested != null && nested.isJsonObject()) {
            JsonElement nestedData = nested.getAsJsonObject().get("data");
            if (nestedData != null && nestedData.isJsonArray()) return nestedData.getAsJsonArray();
        }
        JsonArray single = new JsonArray();
        single.add(obj);
        return single;
    }

    private static String formatLiters(double liters) {
        return String.format(Locale.US, "%.2f", liters);
    }

    private static class LinearLayoutParams extends LinearLayout.LayoutParams {
        LinearLayoutParams() {
            super(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            setMargins(0, 0, 0, 10);
        }
    }

    private static class AssignedVehicle {
        int id;
        String name;
        int fuelTypeId;
        String fuelTypeName;

        static List<AssignedVehicle> parseList(JsonElement el) {
            List<AssignedVehicle> out = new ArrayList<>();
            if (el == null || el.isJsonNull()) return out;
            if (el.isJsonArray()) {
                for (JsonElement item : el.getAsJsonArray()) {
                    AssignedVehicle v = fromJsonElement(item);
                    if (v != null && v.id > 0) out.add(v);
                }
            } else {
                AssignedVehicle v = fromJsonElement(el);
                if (v != null && v.id > 0) out.add(v);
            }
            return out;
        }

        static AssignedVehicle fromJsonElement(JsonElement el) {
            if (el == null || el.isJsonNull()) return null;
            AssignedVehicle v = new AssignedVehicle();
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                v.id = FuelAssignment.intOf(obj, "id", 0);
                v.name = FuelAssignment.stringOf(obj, "vehiculo", "");
                if (TextUtils.isEmpty(v.name)) v.name = FuelAssignment.stringOf(obj, "nombre", "Desconocido");
                if (obj.has("tipo_combustible") && obj.get("tipo_combustible").isJsonObject()) {
                    JsonObject fuel = obj.getAsJsonObject("tipo_combustible");
                    v.fuelTypeId = FuelAssignment.intOf(fuel, "id", 0);
                    v.fuelTypeName = FuelAssignment.stringOf(fuel, "nombre", "N/A");
                } else {
                    v.fuelTypeId = FuelAssignment.intOf(obj, "tipo_combustible_id", 0);
                    v.fuelTypeName = FuelAssignment.stringOf(obj, "tipo_combustible", "N/A");
                }
            } else if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
                v.id = el.getAsInt();
                v.name = "Vehículo " + v.id;
                v.fuelTypeName = "N/A";
            } else {
                return null;
            }
            if (TextUtils.isEmpty(v.name)) v.name = "Desconocido";
            if (TextUtils.isEmpty(v.fuelTypeName)) v.fuelTypeName = "N/A";
            return v;
        }
    }

    private static class FuelAssignment {
        int id;
        int userId;
        int fuelTypeId;
        int vehicleId;
        String fuelType;
        double qty;
        boolean bidonAuthorized;

        static FuelAssignment fromJson(JsonObject obj, int fallbackUserId) {
            FuelAssignment a = new FuelAssignment();
            a.id = intOf(obj, "id", 0);
            a.userId = intOf(obj, "user_id", fallbackUserId);
            a.fuelTypeId = intOf(obj, "id_tipocombustible", intOf(obj, "tipo_combustible_id", 0));
            a.bidonAuthorized = parseBidonAuthorized(obj);
            a.vehicleId = resolveLegacyVehicleId(obj);
            a.qty = doubleOf(obj, "cantidad_asignada", doubleOf(obj, "cantidad", 0));
            a.fuelType = stringOf(obj, "tipo_combustible", "");
            if (TextUtils.isEmpty(a.fuelType) && obj.has("combustible")) {
                a.fuelType = stringOf(obj, "combustible", "");
            }
            if (TextUtils.isEmpty(a.fuelType)) a.fuelType = "Combustible " + a.fuelTypeId;
            return a;
        }

        static boolean parseBidonAuthorized(JsonObject obj) {
            JsonElement bidon = null;
            if (obj.has("user") && obj.get("user").isJsonObject()) {
                bidon = obj.getAsJsonObject("user").get("autorizado_bidon");
            }
            if (bidon == null || bidon.isJsonNull()) bidon = obj.get("autorizado_bidon");
            if (bidon == null || bidon.isJsonNull()) return false;
            try {
                if (bidon.isJsonPrimitive()) {
                    if (bidon.getAsJsonPrimitive().isBoolean()) return bidon.getAsBoolean();
                    String raw = bidon.getAsString();
                    return "1".equals(raw) || "true".equalsIgnoreCase(raw);
                }
            } catch (Exception ignored) {
                try { return bidon.getAsInt() == 1; } catch (Exception e) { return false; }
            }
            return false;
        }

        static int resolveLegacyVehicleId(JsonObject obj) {
            if (!obj.has("vehiculo_id") || obj.get("vehiculo_id").isJsonNull()) {
                if (obj.has("vehiculo") && obj.get("vehiculo").isJsonObject()) {
                    return intOf(obj.getAsJsonObject("vehiculo"), "id", 0);
                }
                return 0;
            }
            JsonElement vehiculoId = obj.get("vehiculo_id");
            if (vehiculoId.isJsonPrimitive() && vehiculoId.getAsJsonPrimitive().isNumber()) {
                return vehiculoId.getAsInt();
            }
            if (vehiculoId.isJsonObject()) {
                return intOf(vehiculoId.getAsJsonObject(), "id", 0);
            }
            if (vehiculoId.isJsonArray() && vehiculoId.getAsJsonArray().size() > 0) {
                JsonElement first = vehiculoId.getAsJsonArray().get(0);
                if (first.isJsonObject()) return intOf(first.getAsJsonObject(), "id", 0);
                if (first.isJsonPrimitive() && first.getAsJsonPrimitive().isNumber()) return first.getAsInt();
            }
            return 0;
        }

        static int intOf(JsonObject obj, String key, int fallback) {
            try { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback; }
            catch (Exception e) { return fallback; }
        }

        static double doubleOf(JsonObject obj, String key, double fallback) {
            try { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsDouble() : fallback; }
            catch (Exception e) { return fallback; }
        }

        static String stringOf(JsonObject obj, String key, String fallback) {
            try {
                if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
                JsonElement el = obj.get(key);
                if (el.isJsonObject()) {
                    JsonObject nested = el.getAsJsonObject();
                    if (nested.has("nombre")) return nested.get("nombre").getAsString();
                    if (nested.has("name")) return nested.get("name").getAsString();
                }
                return el.getAsString();
            } catch (Exception e) {
                return fallback;
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
