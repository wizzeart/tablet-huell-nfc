package com.petronova.kiosk.network;

import com.petronova.kiosk.config.AppConfig;
import com.petronova.kiosk.util.FileLogger;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bouncycastle.asn1.pkcs.RSAPrivateKey;
import org.bouncycastle.crypto.params.RSAPrivateCrtKeyParameters;
import org.bouncycastle.crypto.signers.RSADigestSigner;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Cliente REST autenticado de la API externa Petronova.
 *
 * Porta 1:1 el proxy de api.py (get_external_token + proxy_request + endpoints /proxy/*).
 *
 * Mecanismo de autenticación (replica get_external_token(), api.py:154):
 *   1. Construir cadena canónica: agent_id\ntimestamp\nnonce\nmethod\npath\nbody_hash
 *   2. Firmar con RSA PKCS#1 v1.5 + SHA-256 usando PRIVATE_KEY_PEM
 *   3. POST a {BASE_URL}external/token → recibe access_token
 *   4. Usar token en header Authorization: Bearer {token} para las llamadas reales
 *
 * NOTA PKCS#1 vs PKCS#8:
 *   La clave en config.py es PKCS#1 ("BEGIN RSA PRIVATE KEY").
 *   Java estándar solo acepta PKCS#8. Usamos BouncyCastle PEMParser para leerla
 *   sin necesidad de convertir la clave.
 *
 * NOTA TLS:
 *   El cliente Python usa verify=False. Este cliente valida el certificado por defecto
 *   (comportamiento correcto para producción). Si la API tiene un certificado autofirmado,
 *   agregar un TrustManager personalizado aquí.
 *
 * NOTA: todos los métodos son bloqueantes. Llamar siempre desde hilo de fondo.
 */
public class PetronovaApiClient {

    private static final String TAG = "PetronovaApi";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static PetronovaApiClient instance;

    private final OkHttpClient http;
    private final Gson         gson = new Gson();
    private final SecureRandom rng  = new SecureRandom();
    private final String       privateKeyPem;

    private PetronovaApiClient(String privateKeyPem) {
        this.privateKeyPem = privateKeyPem;
        http = new OkHttpClient.Builder()
            .connectTimeout(AppConfig.HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(AppConfig.HTTP_TIMEOUT_SECONDS,    TimeUnit.SECONDS)
            .writeTimeout(AppConfig.HTTP_TIMEOUT_SECONDS,   TimeUnit.SECONDS)
            // Uncomment if Petronova uses self-signed certificate:
            // .hostnameVerifier((h, s) -> true)
            .build();
    }

    /** Inicializa el singleton cargando la clave RSA desde assets. Llamar una vez en Application.onCreate(). */
    public static synchronized void init(android.content.Context context) throws IOException {
        if (instance == null) {
            String pem = AppConfig.loadPrivateKeyPem(context);
            instance = new PetronovaApiClient(pem);
        }
    }

    public static synchronized PetronovaApiClient getInstance() {
        if (instance == null) throw new IllegalStateException("PetronovaApiClient no inicializado — llama init(context) primero");
        return instance;
    }

    // ─── Autenticación RSA ────────────────────────────────────────────────────

    /**
     * Obtiene un Bearer token firmando con la clave privada RSA.
     * Equivalente: get_external_token() en api.py:154
     */
    private String getExternalToken() throws IOException {
        String method   = "POST";
        String path     = "/external/token";
        long   timestamp = System.currentTimeMillis() / 1000L;
        String nonce    = randomHex(16);
        String bodyHash = sha256Hex("");

        // Cadena canónica — idéntica al canonical en api.py:162
        String canonical = String.join("\n",
            AppConfig.AGENT_ID,
            String.valueOf(timestamp),
            nonce,
            method,
            path,
            bodyHash
        );

        String signature = signRsa(canonical);

        Map<String, Object> payload = new HashMap<>();
        payload.put("agent_id",  AppConfig.AGENT_ID);
        payload.put("timestamp", timestamp);
        payload.put("nonce",     nonce);
        payload.put("signature", signature);
        payload.put("method",    method);
        payload.put("path",      path);
        payload.put("body_hash", bodyHash);

        String url = AppConfig.PETRONOVA_BASE_URL + "external/token";
        RequestBody body = RequestBody.create(gson.toJson(payload), JSON);
        Request req = new Request.Builder()
            .url(url)
            .post(body)
            .addHeader("Content-Type", "application/json")
            .build();

        try (Response resp = http.newCall(req).execute()) {
            String responseBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("Token RSA fallido — HTTP " + resp.code() + ": " + responseBody);
            }
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            String token = json.get("access_token").getAsString();
            FileLogger.d(TAG, "Token RSA obtenido para agente '" + AppConfig.AGENT_ID + "'");
            return token;
        }
    }

    // ─── Proxy genérico ───────────────────────────────────────────────────────

    /**
     * Realiza una llamada autenticada a la API Petronova.
     * Equivalente: proxy_request() en api.py:200
     */
    private ApiResult proxyRequest(String method, String path,
                                   Map<String, String> params,
                                   Map<String, Object> jsonBody) {
        try {
            String token = getExternalToken();
            String url   = AppConfig.PETRONOVA_BASE_URL + path;

            // Añadir query params
            if (params != null && !params.isEmpty()) {
                StringBuilder sb = new StringBuilder(url).append("?");
                for (Map.Entry<String, String> e : params.entrySet()) {
                    sb.append(e.getKey()).append("=").append(e.getValue()).append("&");
                }
                url = sb.toString().replaceAll("&$", "");
            }

            RequestBody reqBody = null;
            if (jsonBody != null) {
                reqBody = RequestBody.create(gson.toJson(jsonBody), JSON);
            } else if ("POST".equals(method) || "PUT".equals(method)) {
                reqBody = RequestBody.create("", JSON);
            }

            Request.Builder builder = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .addHeader("Content-Type",  "application/json")
                .addHeader("Accept",        "application/json");

            switch (method) {
                case "GET":    builder.get(); break;
                case "POST":   builder.post(reqBody); break;
                case "PUT":    builder.put(reqBody); break;
                case "DELETE": builder.delete(); break;
                default: return ApiResult.error("Método HTTP no soportado: " + method);
            }

            try (Response resp = http.newCall(builder.build()).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    return ApiResult.error("HTTP " + resp.code() + ": " + respBody);
                }
                try {
                    JsonElement data = JsonParser.parseString(respBody);
                    return ApiResult.success(data);
                } catch (Exception e) {
                    return ApiResult.success(JsonParser.parseString("\"" + respBody + "\""));
                }
            }
        } catch (IOException e) {
            FileLogger.e(TAG, "Error en proxyRequest " + method + " " + path, e);
            return ApiResult.error(e.getMessage());
        }
    }

    // ─── Endpoints (equivalentes a los /proxy/* en api.py) ───────────────────

    /** GET /proxy/asignaciones?user_id=... → external/asignaciones-usuario/ */
    public ApiResult getAsignaciones(int userId) {
        Map<String, String> p = new HashMap<>();
        p.put("user_id", String.valueOf(userId));
        return proxyRequest("GET", "external/asignaciones-usuario/", p, null);
    }

    /** PUT /proxy/asignaciones/{id}/ → external/asignaciones-usuario/{id}/ */
    public ApiResult updateAsignacion(int assignmentId, Map<String, Object> data) {
        return proxyRequest("PUT", "external/asignaciones-usuario/" + assignmentId + "/", null, data);
    }

    /** POST /proxy/consumos/ → external/consumos/ */
    public ApiResult registrarConsumo(Map<String, Object> data) {
        return proxyRequest("POST", "external/consumos/", null, data);
    }

    /** GET /proxy/consumos?user_id=... → external/consumos/ */
    public ApiResult getConsumos(int userId) {
        Map<String, String> p = new HashMap<>();
        p.put("user_id", String.valueOf(userId));
        return proxyRequest("GET", "external/consumos/", p, null);
    }

    /** GET /proxy/tanques?ubicacion_id=... → external/tanques-combustibles/ */
    public ApiResult getTanques(int ubicacionId) {
        Map<String, String> p = new HashMap<>();
        p.put("ubicacion_id", String.valueOf(ubicacionId));
        return proxyRequest("GET", "external/tanques-combustibles/", p, null);
    }

    /** GET /proxy/ubicaciones → external/catalog/ubicaciones */
    public ApiResult getUbicaciones() {
        return proxyRequest("GET", "external/catalog/ubicaciones", null, null);
    }

    /** GET /proxy/verificar-pistero-ubicacion?user_id=...&ubicacion_id=... */
    public ApiResult verificarPisteroUbicacion(int userId, int ubicacionId) {
        Map<String, String> p = new HashMap<>();
        p.put("user_id",      String.valueOf(userId));
        p.put("ubicacion_id", String.valueOf(ubicacionId));
        return proxyRequest("GET", "external/consumos/verificar-pistero-ubicacion", p, null);
    }

    // ─── Criptografía RSA (BouncyCastle) ─────────────────────────────────────

    /**
     * Firma la cadena con la clave privada RSA PKCS#1 v1.5 + SHA-256.
     * Equivalente: private_key.sign(canonical.encode(), PKCS1v15(), SHA256()) en api.py:166
     *
     * Usa BouncyCastle PEMParser para leer PKCS#1 ("BEGIN RSA PRIVATE KEY")
     * sin necesidad de convertir a PKCS#8.
     */
    private String signRsa(String data) throws IOException {
        try (PEMParser parser = new PEMParser(new StringReader(privateKeyPem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter()
                .setProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
            PrivateKey privateKey;
            if (obj instanceof PEMKeyPair) {
                privateKey = converter.getPrivateKey(((PEMKeyPair) obj).getPrivateKeyInfo());
            } else {
                throw new IOException("Formato de clave PEM no reconocido: " + obj.getClass());
            }
            Signature sig = Signature.getInstance("SHA256withRSA", new org.bouncycastle.jce.provider.BouncyCastleProvider());
            sig.initSign(privateKey);
            sig.update(data.getBytes(StandardCharsets.UTF_8));
            byte[] signed = sig.sign();
            return Base64.getEncoder().encodeToString(signed);
        } catch (Exception e) {
            throw new IOException("Error firmando con RSA: " + e.getMessage(), e);
        }
    }

    // ─── Utilidades ──────────────────────────────────────────────────────────

    /** Genera N bytes aleatorios y los devuelve como string hexadecimal (equivalente secrets.token_hex(N)). */
    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        rng.nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** SHA-256 hex del string dado (equivalente hashlib.sha256(b'').hexdigest()). */
    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ─── Result type ─────────────────────────────────────────────────────────

    public static class ApiResult {
        public final boolean     success;
        public final JsonElement data;
        public final String      error;

        private ApiResult(boolean success, JsonElement data, String error) {
            this.success = success;
            this.data    = data;
            this.error   = error;
        }
        public static ApiResult success(JsonElement data) { return new ApiResult(true,  data, null); }
        public static ApiResult error(String msg)          { return new ApiResult(false, null, msg);  }
    }
}
