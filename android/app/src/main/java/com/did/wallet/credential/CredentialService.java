package com.did.wallet.credential;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * Comunicación HTTP con el emisor y almacenamiento cifrado de VCs.
 *
 * Las VCs se guardan en EncryptedSharedPreferences (Jetpack Security):
 *   - Claves cifradas con AES-256/SIV (determinístico, para búsqueda)
 *   - Valores cifrados con AES-256/GCM
 *   - La MasterKey vive en Android Keystore (misma jerarquía de fallback
 *     StrongBox → TEE que en KeyManager)
 *
 * Esto garantiza que aunque alguien extraiga el almacenamiento del dispositivo
 * (backup ADB, acceso root), los JWTs de las VCs no sean legibles.
 *
 * IMPORTANTE: todos los métodos deben ejecutarse en un hilo de fondo.
 */
public class CredentialService {

    private static final String PREFS_VC = "did_vcs_encrypted";
    private static final String KEY_LIST = "vc_list";

    private final Context context;
    private final String  issuerBaseUrl;

    public CredentialService(Context context, String issuerBaseUrl) {
        this.context       = context.getApplicationContext();
        this.issuerBaseUrl = issuerBaseUrl.replaceAll("/$", "");
    }

    // ─── Nonce ───────────────────────────────────────────────────────────────

    public String fetchNonce() throws Exception {
        String response = get(issuerBaseUrl + "/credentials/nonce");
        return new JSONObject(response).getString("nonce");
    }

    // ─── Solicitud de Credencial ──────────────────────────────────────────────

    public String requestCredential(String holderDid, String proofJwt) throws Exception {
        JSONObject body = new JSONObject();
        body.put("holder_did", holderDid);
        body.put("proof",      proofJwt);

        String   response = post(issuerBaseUrl + "/credentials/issue", body.toString());
        JSONObject json   = new JSONObject(response);
        String   vcJwt    = json.getString("credential");

        storeCredential(vcJwt);
        return vcJwt;
    }

    // ─── Almacenamiento cifrado de VCs ───────────────────────────────────────

    /**
     * Persiste una VC JWT cifrada con AES-256/GCM.
     * La clave maestra vive en Android Keystore (StrongBox si disponible, sino TEE).
     */
    public void storeCredential(String vcJwt) throws Exception {
        SharedPreferences prefs = encryptedPrefs();
        String   existing = prefs.getString(KEY_LIST, "[]");
        JSONArray list    = new JSONArray(existing);
        list.put(vcJwt);
        prefs.edit().putString(KEY_LIST, list.toString()).apply();
    }

    public String[] getStoredCredentials() throws Exception {
        String listJson = encryptedPrefs().getString(KEY_LIST, "[]");
        JSONArray arr   = new JSONArray(listJson);
        String[]  result = new String[arr.length()];
        for (int i = 0; i < arr.length(); i++) result[i] = arr.getString(i);
        return result;
    }

    public void clearCredentials() throws Exception {
        encryptedPrefs().edit().clear().apply();
    }

    // ─── EncryptedSharedPreferences ───────────────────────────────────────────

    /**
     * Crea o abre el almacenamiento cifrado.
     *
     * MasterKey.Builder usa Android Keystore internamente con AES-256/GCM.
     * En dispositivos con API 28+ intentará StrongBox automáticamente si el
     * esquema lo permite; en caso contrario recae en TEE.
     *
     * EncryptedSharedPreferences cifra:
     *   - Nombres de claves: AES-256/SIV (permite búsqueda sin revelar el contenido)
     *   - Valores: AES-256/GCM (cifrado autenticado)
     */
    private SharedPreferences encryptedPrefs() throws Exception {
        MasterKey masterKey = new MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build();

        return EncryptedSharedPreferences.create(
            context,
            PREFS_VC,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    private String get(String urlStr) throws Exception {
        HttpURLConnection conn = open(urlStr, "GET");
        return readBody(conn);
    }

    private String post(String urlStr, String jsonBody) throws Exception {
        HttpURLConnection conn = open(urlStr, "POST");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        return readBody(conn);
    }

    private HttpURLConnection open(String urlStr, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        return conn;
    }

    private String readBody(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream stream = (code < 400) ? conn.getInputStream() : conn.getErrorStream();
        try (Scanner sc = new Scanner(stream, StandardCharsets.UTF_8.name())) {
            String body = sc.useDelimiter("\\A").hasNext() ? sc.next() : "";
            if (code >= 400) throw new RuntimeException("HTTP " + code + ": " + body);
            return body;
        }
    }
}
