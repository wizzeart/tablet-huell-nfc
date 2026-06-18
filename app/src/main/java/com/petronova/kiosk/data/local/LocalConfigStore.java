package com.petronova.kiosk.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Persistencia local de configuración del kiosko.
 * Equivalente de tanques.json, ubicacion.json y pistero.json en el servidor Flask.
 *
 * Usa SharedPreferences (JSON strings) en lugar de archivos sueltos.
 * Endpoints equivalentes:
 *   GET/POST /tanques   → getTankSelections() / saveTankSelections()
 *   GET/POST /ubicacion → getUbicacionId() / saveUbicacionId()
 *   GET/POST/DELETE /pistero → getPistero() / savePistero() / clearPistero()
 */
public class LocalConfigStore {

    private static final String PREFS_NAME    = "kiosk_config";
    private static final String KEY_TANQUES   = "tanques";
    private static final String KEY_UBICACION = "ubicacion_id";
    private static final String KEY_PISTERO   = "pistero";

    private final SharedPreferences prefs;
    private final Gson              gson = new Gson();

    public LocalConfigStore(Context context) {
        prefs = context.getApplicationContext()
                       .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ─── Tanques: Map<tipo_combustible_id, tank_id> ──────────────────────────

    public Map<String, String> getTankSelections() {
        String json = prefs.getString(KEY_TANQUES, "{}");
        Type type = new TypeToken<Map<String, String>>() {}.getType();
        Map<String, String> result = gson.fromJson(json, type);
        return result != null ? result : new HashMap<>();
    }

    public void saveTankSelections(Map<String, String> selections) {
        prefs.edit().putString(KEY_TANQUES, gson.toJson(selections)).apply();
    }

    // ─── Ubicacion ────────────────────────────────────────────────────────────

    public String getUbicacionId() {
        return prefs.getString(KEY_UBICACION, "2");
    }

    public void saveUbicacionId(String id) {
        prefs.edit().putString(KEY_UBICACION, id).apply();
    }

    // ─── Pistero ──────────────────────────────────────────────────────────────

    public PisteroData getPistero() {
        String json = prefs.getString(KEY_PISTERO, null);
        if (json == null) return null;
        return gson.fromJson(json, PisteroData.class);
    }

    public void savePistero(PisteroData pistero) {
        prefs.edit().putString(KEY_PISTERO, gson.toJson(pistero)).apply();
    }

    public void clearPistero() {
        prefs.edit().remove(KEY_PISTERO).apply();
    }

    // ─── Modelo ───────────────────────────────────────────────────────────────

    public static class PisteroData {
        public int    userId;
        public String nombre;
        public String apellidos;

        public PisteroData(int userId, String nombre, String apellidos) {
            this.userId    = userId;
            this.nombre    = nombre != null ? nombre : "";
            this.apellidos = apellidos != null ? apellidos : "";
        }

        public String getFullName() {
            String full = nombre;
            if (apellidos != null && !apellidos.isEmpty()) full += " " + apellidos;
            return full;
        }
    }
}
