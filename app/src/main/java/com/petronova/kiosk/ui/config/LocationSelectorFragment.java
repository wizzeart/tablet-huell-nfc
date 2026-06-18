package com.petronova.kiosk.ui.config;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.petronova.kiosk.R;
import com.petronova.kiosk.data.local.LocalConfigStore;
import com.petronova.kiosk.databinding.FragmentLocationSelectorBinding;
import com.petronova.kiosk.network.PetronovaApiClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Selector de ubicaciones.
 * Equivalente del modal #locationSelectorModal + showLocationSelector() en scanning.js:1837.
 *
 * Flujo:
 *   1. Carga ubicacion_id guardada desde LocalConfigStore (≡ GET /ubicacion)
 *   2. Llama PetronovaApiClient.getUbicaciones() (≡ GET /proxy/ubicaciones)
 *   3. Renderiza tarjetas seleccionables
 *   4. Al guardar: LocalConfigStore.saveUbicacionId() (≡ POST /ubicacion)
 */
public class LocationSelectorFragment extends Fragment {

    private FragmentLocationSelectorBinding binding;
    private LocalConfigStore                store;
    private String                          selectedId;
    private final ExecutorService           bg = Executors.newSingleThreadExecutor();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentLocationSelectorBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        store      = new LocalConfigStore(requireContext());
        selectedId = store.getUbicacionId();

        binding.btnLocationClose.setOnClickListener(v ->
            requireActivity().getSupportFragmentManager().popBackStack());

        binding.btnLocationSave.setOnClickListener(v -> saveLocation());

        loadUbicaciones();
    }

    // ─── Carga desde API ─────────────────────────────────────────────────────

    private void loadUbicaciones() {
        showStatus(getString(R.string.location_loading), R.color.color_text_secondary);

        bg.execute(() -> {
            PetronovaApiClient.ApiResult result =
                PetronovaApiClient.getInstance().getUbicaciones();

            if (!result.success) {
                postStatus(getString(R.string.location_error), R.color.color_error);
                return;
            }

            // external API devuelve { "data": [...] } o { "data": { "data": [...] } }
            JsonArray arr = new JsonArray();
            try {
                JsonObject outer = result.data.getAsJsonObject();
                if (outer.has("data")) {
                    JsonElement inner = outer.get("data");
                    if (inner.isJsonArray()) {
                        arr = inner.getAsJsonArray();
                    } else if (inner.isJsonObject() && inner.getAsJsonObject().has("data")) {
                        arr = inner.getAsJsonObject().get("data").getAsJsonArray();
                    }
                }
            } catch (Exception ignored) {}

            if (arr.size() == 0) {
                postStatus(getString(R.string.location_empty), R.color.color_text_secondary);
                return;
            }

            final JsonArray finalArr = arr;
            requireActivity().runOnUiThread(() -> renderUbicacionesCaching(finalArr));
        });
    }

    // ─── Renderizado ─────────────────────────────────────────────────────────

    private void renderUbicaciones(JsonArray arr) {
        binding.tvLocationStatus.setVisibility(View.GONE);
        binding.llLocationContent.removeAllViews();

        for (JsonElement el : arr) {
            try {
                JsonObject u   = el.getAsJsonObject();
                String id      = u.get("id").getAsString();
                String nombre  = u.has("nombre") ? u.get("nombre").getAsString() : id;
                View card = buildLocationCard(id, nombre);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, dp(12));
                card.setLayoutParams(lp);
                binding.llLocationContent.addView(card);
            } catch (Exception ignored) {}
        }

        binding.btnLocationSave.setVisibility(selectedId != null ? View.VISIBLE : View.GONE);
    }

    private View buildLocationCard(String id, String nombre) {
        boolean selected = id.equals(selectedId);

        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0x1A00FF96 : 0x140096FF);
        bg.setStroke(dp(2), selected ? 0xFF00FF96 : 0xFF0096FF);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);

        // Nombre de la ubicación
        TextView tvNombre = new TextView(requireContext());
        tvNombre.setText(nombre);
        tvNombre.setTextSize(18f);
        tvNombre.setTextColor(0xFFFFFFFF);
        tvNombre.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lpNombre = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvNombre.setLayoutParams(lpNombre);
        card.addView(tvNombre);

        // Checkmark si está seleccionada
        if (selected) {
            TextView tvCheck = new TextView(requireContext());
            tvCheck.setText(getString(R.string.location_selected));
            tvCheck.setTextSize(13f);
            tvCheck.setTextColor(0xFF00FF96);
            tvCheck.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            card.addView(tvCheck);
        }

        // ID de la ubicación
        TextView tvId = new TextView(requireContext());
        tvId.setText("ID: " + id);
        tvId.setTextSize(12f);
        tvId.setTextColor(0xFF9CA3AF);
        tvId.setPadding(dp(12), 0, 0, 0);
        card.addView(tvId);

        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> {
            selectedId = id;
            binding.btnLocationSave.setVisibility(View.VISIBLE);
            renderUbicacionesCurrentData();
        });

        return card;
    }

    private JsonArray lastData = null;

    private void renderUbicacionesCurrentData() {
        if (lastData != null) renderUbicaciones(lastData);
    }

    // Override to cache data for re-render on selection change
    private void renderUbicacionesCaching(JsonArray arr) {
        lastData = arr;
        renderUbicaciones(arr);
    }

    // ─── Guardar (≡ POST /ubicacion) ─────────────────────────────────────────

    private void saveLocation() {
        if (selectedId == null) return;
        store.saveUbicacionId(selectedId);
        showSuccessAndClose(getString(R.string.location_saved_ok));
    }

    // ─── Helpers UI ──────────────────────────────────────────────────────────

    private void showStatus(String msg, int colorRes) {
        binding.tvLocationStatus.setVisibility(View.VISIBLE);
        binding.tvLocationStatus.setText(msg);
        binding.tvLocationStatus.setTextColor(getColor(colorRes));
        binding.llLocationContent.removeAllViews();
        binding.btnLocationSave.setVisibility(View.GONE);
        com.petronova.kiosk.audio.TtsManager.getInstance().speak(msg);
    }

    private void postStatus(String msg, int colorRes) {
        if (getActivity() != null) {
            requireActivity().runOnUiThread(() -> showStatus(msg, colorRes));
        }
    }

    private void showSuccessAndClose(String msg) {
        binding.tvLocationStatus.setVisibility(View.VISIBLE);
        binding.tvLocationStatus.setText(msg);
        binding.tvLocationStatus.setTextColor(getColor(R.color.color_success));
        binding.btnLocationSave.setEnabled(false);
        com.petronova.kiosk.audio.TtsManager.getInstance().speak(msg);
        binding.getRoot().postDelayed(() -> {
            if (getActivity() != null)
                requireActivity().getSupportFragmentManager().popBackStack();
        }, 1200);
    }

    private int getColor(int resId) {
        return requireContext().getColor(resId);
    }

    private int dp(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bg.shutdown();
        binding = null;
    }
}
