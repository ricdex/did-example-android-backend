package com.did.wallet.ui;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.did.wallet.R;
import com.did.wallet.credential.CredentialRequestBuilder;
import com.did.wallet.credential.CredentialService;
import com.did.wallet.did.DIDManager;
import com.did.wallet.presentation.VPBuilder;
import com.did.wallet.security.KeyManager;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * UI de demostración del flujo DID completo:
 *   1. Generar par de claves secp256k1 + DID
 *   2. Solicitar Verifiable Credential al emisor
 *   3. Generar Verifiable Presentation firmada
 *
 * En producción cada acción se lanzaría desde su pantalla correspondiente.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG        = "DIDWallet";
    private static final String ISSUER_URL = "http://10.0.2.2:8080"; // emulador → localhost

    private KeyManager          keyManager;
    private DIDManager          didManager;
    private CredentialService   credentialService;

    private TextView     tvOutput;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        keyManager        = new KeyManager(this);
        didManager        = new DIDManager(keyManager);
        credentialService = new CredentialService(this, ISSUER_URL);

        tvOutput = findViewById(R.id.tv_output);

        Button btnGenKey   = findViewById(R.id.btn_gen_key);
        Button btnReqVC    = findViewById(R.id.btn_req_vc);
        Button btnGenVP    = findViewById(R.id.btn_gen_vp);
        ScrollView scroll  = findViewById(R.id.scroll_output);

        // ── 1. Generar par de claves ─────────────────────────────────────────
        btnGenKey.setOnClickListener(v -> executor.submit(() -> {
            try {
                KeyManager.KeyPairResult result = keyManager.generateAndStore();
                String did   = didManager.getDID();
                String doc   = didManager.buildDIDDocument(did);
                String level = keyManager.getSecurityLevel().name();

                log("✓ Par secp256k1 generado\n"
                    + "Nivel hardware: " + level + "\n\n"
                    + "DID:\n" + did + "\n\n"
                    + "DID Document:\n" + doc);
            } catch (Exception e) {
                log("✗ Error generando claves: " + e.getMessage());
                Log.e(TAG, "Key gen error", e);
            }
        }));

        // ── 2. Solicitar VC ──────────────────────────────────────────────────
        btnReqVC.setOnClickListener(v -> executor.submit(() -> {
            try {
                if (!keyManager.hasKeyPair()) {
                    log("✗ Primero genera el par de claves (paso 1)");
                    return;
                }

                String holderDid = didManager.getDID();

                // 2a. Obtener nonce del emisor
                String nonce = credentialService.fetchNonce();
                log("→ Nonce obtenido: " + nonce);

                // 2b. Construir claims del sujeto
                JSONObject claims = new JSONObject();
                claims.put("givenName",  "Juan");
                claims.put("familyName", "Pérez");
                claims.put("email",      "juan@example.com");

                // 2c. Construir y firmar proof JWT
                String proofJwt = new CredentialRequestBuilder(keyManager, didManager)
                    .holderDid(holderDid)
                    .issuerUrl(ISSUER_URL)
                    .nonce(nonce)
                    .credentialType("UniversityDegreeCredential")
                    .subjectClaims(claims)
                    .build();

                log("→ Proof JWT generado (firmado con secp256k1)");

                // 2d. Enviar al emisor y recibir VC
                String vcJwt = credentialService.requestCredential(holderDid, proofJwt);
                log("✓ VC recibida y almacenada:\n" + vcJwt);

            } catch (Exception e) {
                log("✗ Error solicitando VC: " + e.getMessage());
                Log.e(TAG, "VC request error", e);
            }
        }));

        // ── 3. Generar VP ────────────────────────────────────────────────────
        btnGenVP.setOnClickListener(v -> executor.submit(() -> {
            try {
                String[] vcs = credentialService.getStoredCredentials();
                if (vcs.length == 0) {
                    log("✗ No hay VCs almacenadas. Solicita una primero (paso 2)");
                    return;
                }

                String holderDid = didManager.getDID();

                String vpJwt = new VPBuilder(keyManager, didManager)
                    .holderDid(holderDid)
                    .credentials(vcs)
                    .audience("did:web:verifier.example.com")
                    .nonce("verifier-nonce-" + System.currentTimeMillis())
                    .build();

                log("✓ Verifiable Presentation generada:\n" + vpJwt);

            } catch (Exception e) {
                log("✗ Error generando VP: " + e.getMessage());
                Log.e(TAG, "VP gen error", e);
            }
        }));
    }

    private void log(String msg) {
        runOnUiThread(() -> {
            tvOutput.setText(tvOutput.getText() + "\n\n" + msg);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
