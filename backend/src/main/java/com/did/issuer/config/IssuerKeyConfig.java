package com.did.issuer.config;

import com.did.issuer.service.DIDKeyUtil;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.Security;
import java.util.Base64;

/**
 * Genera o carga el par de claves secp256k1 del emisor al iniciar.
 *
 * Estrategia de resolución de la clave (por prioridad):
 *
 *   1. Variable de entorno ISSUER_KEY_PEM (base64 del PEM)
 *      → producción: Azure Key Vault la inyecta como secret en Container Apps
 *
 *   2. Archivo PEM en disco (issuer.key-file)
 *      → desarrollo local: generado en ./data/issuer_key.pem al primer inicio
 */
@Configuration
public class IssuerKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(IssuerKeyConfig.class);

    static {
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());
    }

    @Value("${issuer.key-file}")
    private String keyFilePath;

    @Bean
    public IssuerKeys issuerKeys() throws Exception {
        KeyPair keyPair = loadKeyPair();

        String pubHex = DIDKeyUtil.compressedPublicKeyHex(keyPair);
        String did    = DIDKeyUtil.buildDIDKey(pubHex);

        log.info("Emisor DID: {}", did);
        return new IssuerKeys(keyPair, pubHex, did);
    }

    private KeyPair loadKeyPair() throws Exception {
        // ── Opción 1: Variable de entorno (producción — Azure Key Vault) ─────────
        String keyPemBase64 = System.getenv("ISSUER_KEY_PEM");
        if (keyPemBase64 != null && !keyPemBase64.isBlank()) {
            log.info("Cargando clave del emisor desde ISSUER_KEY_PEM (Azure Key Vault)");
            byte[] pemBytes = Base64.getDecoder().decode(keyPemBase64.trim());
            return DIDKeyUtil.loadKeyPairFromBytes(pemBytes);
        }

        // ── Opción 2: Archivo PEM local (desarrollo) ─────────────────────────────
        Path keyFile = Path.of(keyFilePath);
        keyFile.toFile().getParentFile().mkdirs();

        if (Files.exists(keyFile)) {
            log.info("Cargando clave del emisor desde {}", keyFile);
            return DIDKeyUtil.loadKeyPair(keyFile);
        }

        log.info("Generando nuevo par secp256k1 para el emisor...");
        KeyPair keyPair = DIDKeyUtil.generateSecp256k1KeyPair();
        DIDKeyUtil.saveKeyPair(keyPair, keyFile);
        log.info("Par guardado en {}", keyFile);
        return keyPair;
    }

    // ─── DTO ─────────────────────────────────────────────────────────────────

    public static final class IssuerKeys {
        public final KeyPair keyPair;
        public final String  publicKeyHex;
        public final String  did;

        public IssuerKeys(KeyPair keyPair, String publicKeyHex, String did) {
            this.keyPair      = keyPair;
            this.publicKeyHex = publicKeyHex;
            this.did          = did;
        }
    }
}
