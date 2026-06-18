package com.petronova.kiosk.ui.config;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.petronova.kiosk.R;
import com.petronova.kiosk.data.local.LocalConfigStore;
import com.petronova.kiosk.databinding.FragmentTankSelectorBinding;
import com.petronova.kiosk.network.PetronovaApiClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Selector de tanques de combustible.
 * Equivalente del modal #tankSelectorModal + showTankSelector() en scanning.js:1676.
 *
 * Flujo:
 *   1. Carga selecciones guardadas en LocalConfigStore (≡ GET /tanques)
 *   2. Obtiene ubicacion_id desde LocalConfigStore (≡ GET /ubicacion)
 *   3. Llama PetronovaApiClient.getTanques() (≡ GET /proxy/tanques)
 *   4. Renderiza grupos por tipo de combustible con tarjetas seleccionables
 *   5. Al guardar: LocalConfigStore.saveTankSelections() (≡ POST /tanques)
 */
public class TankSelectorFragment extends Fragment {

    private FragmentTankSelectorBinding binding;
    private LocalConfigStore            store;
    private Map<String, String>         selections = new LinkedHashMap<>();
    private Map<String, TipoGrupo>      currentGrupos = new LinkedHashMap<>();
    private final ExecutorService       bg = Executors.newSingleThreadExecutor();

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentTankSelectorBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        store = new LocalConfigStore(requireContext());

        binding.btnTankClose.setOnClickListener(v ->
            requireActivity().getSupportFragmentManager().popBackStack());

        binding.btnTankSave.setOnClickListener(v -> saveSelections());

        selections = new LinkedHashMap<>(store.getTankSelections());
        loadTanques();
    }

    // ─── Carga desde API ─────────────────────────────────────────────────────

    private void loadTanques() {
        showStatus(getString(R.string.tank_loading), R.color.color_text_secondary);

        bg.execute(() -> {
            int ubicacionId;
            try {
                ubicacionId = Integer.parseInt(store.getUbicacionId());
            } catch (NumberFormatException e) {
                ubicacionId = 2;
            }

            PetronovaApiClient.ApiResult result =
                PetronovaApiClient.getInstance().getTanques(ubicacionId);

            if (!result.success) {
                postStatus(getString(R.string.tank_error), R.color.color_error);
                return;
            }

            // Parsear: external API devuelve { "data": [...] }
            JsonArray tanquesArray = new JsonArray();
            try {
                JsonObject outer = result.data.getAsJsonObject();
                if (outer.has("data")) {
                    JsonElement inner = outer.get("data");
                    if (inner.isJsonArray()) {
                        tanquesArray = inner.getAsJsonArray();
                    } else if (inner.isJsonObject() && inner.getAsJsonObject().has("data")) {
                        tanquesArray = inner.getAsJsonObject().get("data").getAsJsonArray();
                    }
                }
            } catch (Exception ignored) {}

            // Filtrar comercializables y agrupar por tipo de combustible
            Map<String, TipoGrupo> grupos = new LinkedHashMap<>();
            for (JsonElement el : tanquesArray) {
                try {
                    JsonObject t = el.getAsJsonObject();
                    boolean comercializable = t.has("comercializable") && t.get("comercializable").getAsBoolean();
                    if (!comercializable) continue;

                    String tipo    = t.has("tipo_combustible") ? t.get("tipo_combustible").getAsString() : "Otro";
                    String tipoId  = t.has("tipo_combustible_id") ? t.get("tipo_combustible_id").getAsString() : "0";
                    String id      = t.get("id").getAsString();
                    String nombre  = t.has("nombre") ? t.get("nombre").getAsString() : id;
                    double cap     = t.has("capacidad") && !t.get("capacidad").isJsonNull() ? t.get("capacidad").getAsDouble() : 0;
                    double nivel   = t.has("nivel_actual") && !t.get("nivel_actual").isJsonNull() ? t.get("nivel_actual").getAsDouble() : 0;
                    double pct     = t.has("porcentaje_llenado") && !t.get("porcentaje_llenado").isJsonNull() ? t.get("porcentaje_llenado").getAsDouble() : 0;
                    String ubicNombre = t.has("ubicacion") && !t.get("ubicacion").isJsonNull() ? t.get("ubicacion").getAsString() : "";

                    TanqueItem item = new TanqueItem(id, tipoId, nombre, cap, nivel, pct, ubicNombre);
                    if (!grupos.containsKey(tipo)) grupos.put(tipo, new TipoGrupo(tipoId));
                    grupos.get(tipo).tanques.add(item);
                } catch (Exception ignored) {}
            }

            final Map<String, TipoGrupo> finalGrupos = grupos;
            requireActivity().runOnUiThread(() -> {
                currentGrupos = finalGrupos;
                renderGrupos(currentGrupos);
            });
        });
    }

    // ─── Renderizado de grupos ────────────────────────────────────────────────

    private void renderGrupos(Map<String, TipoGrupo> grupos) {
        binding.tvTankStatus.setVisibility(View.GONE);
        binding.llTankContent.removeAllViews();

        if (grupos.isEmpty()) {
            showStatus(getString(R.string.tank_empty), R.color.color_text_secondary);
            return;
        }

        for (Map.Entry<String, TipoGrupo> entry : grupos.entrySet()) {
            String tipo  = entry.getKey();
            TipoGrupo gr = entry.getValue();

            // Cabecera del grupo (nombre del combustible)
            TextView tvTipo = new TextView(requireContext());
            tvTipo.setText(tipo.toUpperCase());
            tvTipo.setTextSize(16f);
            tvTipo.setTextColor(getColor(R.color.color_tank));
            tvTipo.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tvTipo.setLetterSpacing(0.1f);
            LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            headerParams.setMargins(0, 0, 0, dp(12));
            tvTipo.setLayoutParams(headerParams);
            binding.llTankContent.addView(tvTipo);

            // Tarjetas de tanques
            for (TanqueItem tanque : gr.tanques) {
                View card = buildTankCard(gr.tipoId, tanque);
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cardParams.setMargins(0, 0, 0, dp(12));
                card.setLayoutParams(cardParams);
                binding.llTankContent.addView(card);
            }

            // Separador entre grupos
            View divider = new View(requireContext());
            divider.setBackgroundColor(0x33FFAA00);
            LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            divParams.setMargins(0, 0, 0, dp(20));
            divider.setLayoutParams(divParams);
            binding.llTankContent.addView(divider);
        }

        binding.btnTankSave.setVisibility(View.VISIBLE);
    }

    private View buildTankCard(String tipoId, TanqueItem t) {
        boolean selected = t.id.equals(selections.get(tipoId));

        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0x1A00FF96 : 0x14000000);
        bg.setStroke(dp(2), selected ? 0xFF00FF96 : 0xFF00D4FF);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        if (selected) card.setElevation(dp(4));

        // Fila 1: nombre + checkmark
        LinearLayout row1 = new LinearLayout(requireContext());
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tvNombre = new TextView(requireContext());
        tvNombre.setText(t.nombre);
        tvNombre.setTextSize(16f);
        tvNombre.setTextColor(0xFFFFFFFF);
        tvNombre.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvNombre.setLayoutParams(lp1);
        row1.addView(tvNombre);

        if (selected) {
            TextView tvCheck = new TextView(requireContext());
            tvCheck.setText(getString(R.string.tank_selected));
            tvCheck.setTextSize(12f);
            tvCheck.setTextColor(0xFF00FF96);
            tvCheck.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            row1.addView(tvCheck);
        }

        LinearLayout.LayoutParams row1Params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row1Params.setMargins(0, 0, 0, dp(10));
        row1.setLayoutParams(row1Params);
        card.addView(row1);

        // Fila 2: capacidad / nivel / llenado
        LinearLayout row2 = new LinearLayout(requireContext());
        row2.setOrientation(LinearLayout.HORIZONTAL);

        row2.addView(makeDataPair(getString(R.string.tank_cap),
            String.format("%,.0f L", t.capacidad), 0xFFFFFFFF));
        row2.addView(makeDataPair(getString(R.string.tank_level),
            String.format("%,.0f L", t.nivel), 0xFF00D4FF));

        int pctColor = t.porcentaje < 20 ? 0xFFFF3333 : 0xFF00FF96;
        row2.addView(makeDataPair(getString(R.string.tank_fill),
            String.format("%.1f%%", t.porcentaje), pctColor));

        LinearLayout.LayoutParams row2Params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        row2Params.setMargins(0, 0, 0, dp(8));
        row2.setLayoutParams(row2Params);
        card.addView(row2);

        // Barra de progreso (equivalente al div de fill level en JS)
        ProgressBar bar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress((int) t.porcentaje);
        int barColor = t.porcentaje < 20 ? 0xFFFF3333 : (t.porcentaje < 50 ? 0xFFFFAA00 : 0xFF00FF96);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(barColor));

        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        barParams.setMargins(0, 0, 0, dp(6));
        bar.setLayoutParams(barParams);
        card.addView(bar);

        // Ubicación (subtítulo)
        if (!t.ubicacion.isEmpty()) {
            TextView tvUbic = new TextView(requireContext());
            tvUbic.setText(t.ubicacion);
            tvUbic.setTextSize(12f);
            tvUbic.setTextColor(0xFF9CA3AF);
            card.addView(tvUbic);
        }

        // Click: toggle selección
        final String tipoIdFinal = tipoId;
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> {
            if (t.id.equals(selections.get(tipoIdFinal))) {
                selections.remove(tipoIdFinal);
            } else {
                selections.put(tipoIdFinal, t.id);
            }
            // Re-render para actualizar selección visual (≡ selectTank → showTankSelector)
            reRenderCards();
        });

        return card;
    }

    private LinearLayout makeDataPair(String label, String value, int valueColor) {
        LinearLayout ll = new LinearLayout(requireContext());
        ll.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ll.setLayoutParams(lp);

        TextView tvLabel = new TextView(requireContext());
        tvLabel.setText(label);
        tvLabel.setTextSize(11f);
        tvLabel.setTextColor(0xFF9CA3AF);
        ll.addView(tvLabel);

        TextView tvValue = new TextView(requireContext());
        tvValue.setText(value);
        tvValue.setTextSize(13f);
        tvValue.setTextColor(valueColor);
        ll.addView(tvValue);

        return ll;
    }

    private void reRenderCards() {
        // Volver a renderizar para que se reflejen los cambios de selección
        renderGrupos(currentGrupos);
    }

    // ─── Guardar (≡ POST /tanques) ───────────────────────────────────────────

    private void saveSelections() {
        store.saveTankSelections(selections);
        showSuccessAndClose(getString(R.string.tank_saved_ok));
    }

    // ─── Helpers UI ──────────────────────────────────────────────────────────

    private void showStatus(String msg, int colorRes) {
        binding.tvTankStatus.setVisibility(View.VISIBLE);
        binding.tvTankStatus.setText(msg);
        binding.tvTankStatus.setTextColor(getColor(colorRes));
        binding.llTankContent.removeAllViews();
        binding.btnTankSave.setVisibility(View.GONE);
        com.petronova.kiosk.audio.TtsManager.getInstance().speak(msg);
    }

    private void postStatus(String msg, int colorRes) {
        if (getActivity() != null) {
            requireActivity().runOnUiThread(() -> showStatus(msg, colorRes));
        }
    }

    private void showSuccessAndClose(String msg) {
        binding.tvTankStatus.setVisibility(View.VISIBLE);
        binding.tvTankStatus.setText(msg);
        binding.tvTankStatus.setTextColor(getColor(R.color.color_success));
        binding.btnTankSave.setEnabled(false);
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

    // ─── Modelos internos ────────────────────────────────────────────────────

    private static class TipoGrupo {
        final String     tipoId;
        final List<TanqueItem> tanques = new ArrayList<>();
        TipoGrupo(String tipoId) { this.tipoId = tipoId; }
    }

    private static class TanqueItem {
        final String id;
        final String tipoId;
        final String nombre;
        final double capacidad;
        final double nivel;
        final double porcentaje;
        final String ubicacion;
        TanqueItem(String id, String tipoId, String nombre,
                   double capacidad, double nivel, double porcentaje, String ubicacion) {
            this.id         = id;
            this.tipoId     = tipoId;
            this.nombre     = nombre;
            this.capacidad  = capacidad;
            this.nivel      = nivel;
            this.porcentaje = porcentaje;
            this.ubicacion  = ubicacion;
        }
    }
}
