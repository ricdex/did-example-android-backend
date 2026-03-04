package com.did.issuer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Verifica el Proof JWT enviado por el holder para demostrar posesión de la clave privada.
 *
 * Validaciones:
 *   1. Estructura JWT válida (3 partes separadas por '.')
 *   2. alg = ES256K
 *   3. Firma ECDSA secp256k1 válida (clave pública extraída del DID:key)
 *   4. Nonce válido y de un solo uso
 *   5. JWT no expirado
 *   6. iss coincide con holder_did
 */
@Service
public class ProofVerifier {

    private static final Logger log = LoggerFactory.getLogger(ProofVerifier.class);

    private final NonceService  nonceService;
    private final ObjectMapper  mapper = new ObjectMapper();

    public ProofVerifier(NonceService nonceService) {
        this.nonceService = nonceService;
    }

    /**
     * Verifica el proof JWT.
     *
     * @param proofJwt  JWT compacto firmado por el holder
     * @param holderDid DID declarado por el holder
     * @throws IllegalArgumentException si la verificación falla (mensaje descriptivo)
     */
    public void verify(String proofJwt, String holderDid) {
        try {
            String[] parts = proofJwt.split("\\.");
            if (parts.length != 3) throw new IllegalArgumentException("JWT malformado: se esperan 3 partes");

            // Decodificar header y payload
            JsonNode header  = mapper.readTree(DIDKeyUtil.b64urlDecode(parts[0]));
            JsonNode payload = mapper.readTree(DIDKeyUtil.b64urlDecode(parts[1]));

            // 1. Verificar algoritmo
            String alg = header.path("alg").asText();
            if (!"ES256K".equals(alg)) {
                throw new IllegalArgumentException("Algoritmo inválido '" + alg + "': se requiere ES256K");
            }

            // 2. Verificar iss = holderDid
            String iss = payload.path("iss").asText();
            if (!holderDid.equals(iss)) {
                throw new IllegalArgumentException("iss '" + iss + "' no coincide con holder_did");
            }

            // 3. Verificar expiración
            long exp = payload.path("exp").asLong(0);
            if (exp == 0 || System.currentTimeMillis() / 1000 > exp) {
                throw new IllegalArgumentException("JWT expirado o sin campo exp");
            }

            // 4. Verificar y consumir nonce (single-use)
            String nonce = payload.path("nonce").asText(null);
            if (nonce == null || !nonceService.consumeIfValid(nonce)) {
                throw new IllegalArgumentException("Nonce inválido o expirado");
            }

            // 5. Extraer clave pública del did:key y verificar firma ES256K
            byte[] pubKeyBytes  = DIDKeyUtil.publicKeyFromDIDKey(holderDid);
            String signingInput = parts[0] + "." + parts[1];
            byte[] sigBytes     = DIDKeyUtil.b64urlDecode(parts[2]);

            boolean valid = DIDKeyUtil.verifyES256K(
                signingInput.getBytes(StandardCharsets.UTF_8), sigBytes, pubKeyBytes);

            if (!valid) throw new IllegalArgumentException("Firma ES256K inválida");

            log.debug("Proof JWT verificado correctamente para: {}", holderDid);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Error inesperado verificando proof: {}", e.getMessage());
            throw new IllegalArgumentException("Error al verificar proof: " + e.getMessage());
        }
    }
}
