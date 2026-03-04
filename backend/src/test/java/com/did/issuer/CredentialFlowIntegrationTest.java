package com.did.issuer;

import com.did.issuer.service.DIDKeyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas de integración end-to-end de los tres flujos DID.
 * Genera un reporte Markdown en target/reports/ con cada request y response.
 *
 *   Flujo 1 — Identidad del issuer  : GET /issuer/did, GET /issuer/did-document
 *   Flujo 2 — Emisión de VC         : nonce → proof JWT → issue → VC JWT
 *   Flujo 2b — Seguridad            : nonce reutilizado, firma inválida
 *   Flujo 3 — Metadatos del holder  : GET /credentials?holder_did=
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CredentialFlowIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @LocalServerPort
    int port;

    // Estado compartido entre tests (orden garantizado por @Order)
    static String holderDid;
    static String vcJwt;

    // ─── Reporte ──────────────────────────────────────────────────────────────

    static String  baseUrl;
    static PrintWriter report;
    static Path    reportFile;

    static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @BeforeAll
    static void initReport() throws Exception {
        Path dir = Paths.get("target", "reports");
        Files.createDirectories(dir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        reportFile = dir.resolve("test-report-java-" + ts + ".md");
        report = new PrintWriter(Files.newBufferedWriter(reportFile, StandardCharsets.UTF_8));

        report.println("# Reporte de pruebas de integración (Java)");
        report.println();
        report.println("**Fecha:** " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        report.println("**Framework:** Spring Boot `@SpringBootTest` — servidor en memoria (puerto aleatorio)");
        report.println();
        report.println("---");
        report.println();
        report.flush();
    }

    @AfterAll
    static void closeReport() {
        if (report != null) {
            report.println();
            report.println("---");
            report.println();
            report.println("## Resultado");
            report.println();
            report.println("Todos los tests completados.");
            report.flush();
            report.close();
        }
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("  Reporte generado en:");
        System.out.println("  " + (reportFile != null ? reportFile.toAbsolutePath() : "?"));
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    @BeforeEach
    void capturePort() {
        if (baseUrl == null) {
            baseUrl = "http://localhost:" + port;
        }
    }

    // ─── Helpers de reporte ───────────────────────────────────────────────────

    static void reportSection(String title) {
        report.println("## " + title);
        report.println();
    }

    /**
     * Escribe en el reporte el request y la response de una llamada.
     *
     * @param title      Título del bloque
     * @param method     Método HTTP
     * @param path       Path relativo (ej. "/issuer/did")
     * @param reqBody    Cuerpo del request tal como fue enviado (null si no hay body)
     * @param status     Código HTTP de la respuesta
     * @param respBody   Objeto de respuesta (se serializa a JSON pretty-printed)
     */
    static void logRequest(String title,
                           String method, String path,
                           String reqBody,
                           int status, Object respBody) {
        try {
            report.println("### " + title);
            report.println();

            // ── Request ──────────────────────────────────────────────────────
            report.println("**Request**");
            report.println();
            report.println("```");
            report.println(method + " " + baseUrl + path);
            if (reqBody != null) {
                report.println("Content-Type: application/json");
                report.println();
                report.println(prettyRequestBody(reqBody));
            }
            report.println("```");
            report.println();

            // ── Response ─────────────────────────────────────────────────────
            report.println("**Response** — HTTP " + status);
            report.println();
            report.println("```json");
            report.println(MAPPER.writeValueAsString(respBody));
            report.println("```");
            report.println();

            report.println("**Aserciones:**");
            report.println();
            report.flush();
        } catch (Exception e) {
            // nunca fallar un test por un error de reporte
        }
    }

    /** Escribe una aserción pasada en el reporte. */
    static void pass(String label) {
        report.println("- ✓ " + label);
        report.flush();
    }

    /** Escribe una aserción fallida en el reporte. */
    static void fail(String label, String detail) {
        report.println("- ✗ " + label + (detail != null ? " — " + detail : ""));
        report.flush();
    }

    static void reportSectionEnd() {
        report.println();
        report.println("---");
        report.println();
        report.flush();
    }

    /**
     * Formatea el body del request para el reporte.
     * Si algún valor parece un JWT (3 partes separadas por '.'), lo decodifica.
     */
    @SuppressWarnings("unchecked")
    private static String prettyRequestBody(String raw) {
        try {
            Map<String, Object> map = MAPPER.readValue(raw, Map.class);
            LinkedHashMap<String, Object> expanded = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                Object val = e.getValue();
                if (val instanceof String s && s.split("\\.", -1).length == 3
                        && s.contains(".") && !s.contains(" ")) {
                    expanded.put(e.getKey(), decodeJwt(s));
                } else {
                    expanded.put(e.getKey(), val);
                }
            }
            return MAPPER.writeValueAsString(expanded);
        } catch (Exception ex) {
            return raw;
        }
    }

    /**
     * Decodifica un JWT mostrando header y payload legibles.
     * La firma se omite para que el reporte sea más compacto.
     */
    private static Map<String, Object> decodeJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            String header  = new String(DIDKeyUtil.b64urlDecode(parts[0]), StandardCharsets.UTF_8);
            String payload = new String(DIDKeyUtil.b64urlDecode(parts[1]), StandardCharsets.UTF_8);
            LinkedHashMap<String, Object> decoded = new LinkedHashMap<>();
            decoded.put("header",    MAPPER.readValue(header, Object.class));
            decoded.put("payload",   MAPPER.readValue(payload, Object.class));
            decoded.put("signature", "[omitida]");
            return decoded;
        } catch (Exception e) {
            return Map.of("jwt", jwt.substring(0, Math.min(jwt.length(), 80)) + "...");
        }
    }

    // ─── Flujo 1: Identidad del Issuer ───────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("GET /issuer/did — devuelve DID y clave pública del emisor")
    void issuerDIDEndpoint() {
        reportSection("Flujo 1 — Identidad del Issuer");

        ResponseEntity<Map<String, Object>> resp =
            rest.exchange("/issuer/did", HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<>() {});

        logRequest("GET /issuer/did",
                "GET", "/issuer/did", null,
                resp.getStatusCode().value(), resp.getBody());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        pass("HTTP 200 OK");

        Map<String, Object> body = resp.getBody();
        assertThat(body).containsKey("did");
        pass("Respuesta contiene campo 'did'");

        assertThat(body).containsKey("public_key_hex");
        pass("Respuesta contiene campo 'public_key_hex'");

        String did = (String) body.get("did");
        assertThat(did).startsWith("did:key:z");
        pass("DID comienza con 'did:key:z'");

        String pubHex = (String) body.get("public_key_hex");
        assertThat(pubHex).hasSize(66);
        pass("Clave pública = 66 hex chars (33 bytes comprimidos secp256k1)");

        reportSectionEnd();
    }

    @Test
    @Order(2)
    @DisplayName("GET /issuer/did-document — devuelve DID Document W3C válido")
    void issuerDIDDocument() {
        ResponseEntity<String> resp = rest.getForEntity("/issuer/did-document", String.class);

        Object parsed = null;
        try { parsed = MAPPER.readValue(resp.getBody(), Object.class); }
        catch (Exception e) { parsed = resp.getBody(); }

        logRequest("GET /issuer/did-document",
                "GET", "/issuer/did-document", null,
                resp.getStatusCode().value(), parsed);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        pass("HTTP 200 OK");

        String body = resp.getBody();
        assertThat(body).contains("\"@context\"");
        pass("Contiene '@context'");

        assertThat(body).contains("did:key:");
        pass("Contiene 'did:key:'");

        assertThat(body).contains("EcdsaSecp256k1VerificationKey2019");
        pass("Contiene 'EcdsaSecp256k1VerificationKey2019'");

        assertThat(body).contains("verificationMethod");
        pass("Contiene 'verificationMethod'");

        assertThat(body).contains("assertionMethod");
        pass("Contiene 'assertionMethod'");

        reportSectionEnd();
    }

    // ─── Flujo 2: Emisión de Verifiable Credential ───────────────────────────

    @Test
    @Order(3)
    @DisplayName("GET /credentials/nonce — devuelve nonce de un solo uso")
    void getNonce() {
        reportSection("Flujo 2 — Emisión de Verifiable Credential");

        ResponseEntity<Map<String, Object>> resp =
            rest.exchange("/credentials/nonce", HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<>() {});

        logRequest("GET /credentials/nonce",
                "GET", "/credentials/nonce", null,
                resp.getStatusCode().value(), resp.getBody());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        pass("HTTP 200 OK");

        assertThat(resp.getBody()).containsKey("nonce");
        pass("Respuesta contiene campo 'nonce'");

        String nonce = (String) resp.getBody().get("nonce");
        assertThat(nonce).isNotBlank();
        pass("Nonce no está vacío");

        reportSectionEnd();
    }

    @Test
    @Order(4)
    @DisplayName("POST /credentials/issue — emite VC JWT tras verificar proof ES256K")
    @SuppressWarnings("unchecked")
    void issueCredential() throws Exception {
        // 1. Generar par secp256k1 del holder
        KeyPair holderKeys = DIDKeyUtil.generateSecp256k1KeyPair();
        String  pubHex     = DIDKeyUtil.compressedPublicKeyHex(holderKeys);
        holderDid          = DIDKeyUtil.buildDIDKey(pubHex);
        String  holderKid  = holderDid + "#" + holderDid.substring("did:key:".length());

        // 2. Obtener nonce fresco
        ResponseEntity<Map<String, Object>> nonceResp =
            rest.exchange("/credentials/nonce", HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<>() {});
        String nonce = (String) nonceResp.getBody().get("nonce");

        logRequest("GET /credentials/nonce (paso previo a la emisión)",
                "GET", "/credentials/nonce", null,
                nonceResp.getStatusCode().value(), nonceResp.getBody());
        pass("Nonce obtenido: " + nonce);
        reportSectionEnd();

        // 3. Construir y firmar Proof JWT
        long now = System.currentTimeMillis() / 1000;
        String header  = "{\"alg\":\"ES256K\",\"typ\":\"openid4vci-proof+jwt\",\"kid\":\"" + holderKid + "\"}";
        String payload = "{\"iss\":\"" + holderDid + "\",\"aud\":\"http://localhost\","
            + "\"iat\":" + now + ",\"exp\":" + (now + 300) + ","
            + "\"nonce\":\"" + nonce + "\",\"credential_type\":\"UniversityDegreeCredential\","
            + "\"subject_claims\":{\"givenName\":\"Juan\",\"familyName\":\"Pérez\"}}";

        byte[] privBytes = DIDKeyUtil.to32Bytes(((ECPrivateKey) holderKeys.getPrivate()).getS());
        String proofJwt  = DIDKeyUtil.buildJWT(header, payload, privBytes);

        // 4. Solicitar la VC al issuer
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String reqBody = "{\"holder_did\":\"" + holderDid + "\",\"proof\":\"" + proofJwt + "\"}";

        ResponseEntity<Map<String, Object>> resp =
            rest.exchange("/credentials/issue", HttpMethod.POST,
                new HttpEntity<>(reqBody, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        // Construir versión expandida de la respuesta para el reporte (VC JWT decodificada)
        Map<String, Object> respForReport = new LinkedHashMap<>();
        if (resp.getBody() != null) {
            for (Map.Entry<String, Object> e : resp.getBody().entrySet()) {
                if ("credential".equals(e.getKey()) && e.getValue() instanceof String s) {
                    respForReport.put(e.getKey(), decodeJwt(s));
                } else {
                    respForReport.put(e.getKey(), e.getValue());
                }
            }
        }

        logRequest("POST /credentials/issue",
                "POST", "/credentials/issue", reqBody,
                resp.getStatusCode().value(), respForReport);

        // 5. Verificar respuesta
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        pass("HTTP 200 OK");

        assertThat(resp.getBody()).containsKey("credential");
        pass("Respuesta contiene campo 'credential'");

        vcJwt = (String) resp.getBody().get("credential");
        assertThat(vcJwt).isNotBlank();

        String[] parts = vcJwt.split("\\.");
        assertThat(parts).hasSize(3);
        pass("VC es un JWT de 3 partes (header.payload.signature)");

        String vcPayload = new String(DIDKeyUtil.b64urlDecode(parts[1]));
        assertThat(vcPayload).contains("\"iss\"");
        pass("VC contiene claim 'iss'");

        assertThat(vcPayload).contains("\"sub\"");
        pass("VC contiene claim 'sub'");

        assertThat(vcPayload).contains("UniversityDegreeCredential");
        pass("VC contiene 'UniversityDegreeCredential'");

        assertThat(vcPayload).contains("Juan");
        pass("VC contiene claims del sujeto (givenName: Juan)");

        assertThat(vcPayload).contains(holderDid);
        pass("VC referencia el DID del holder en 'sub'");

        reportSectionEnd();
    }

    // ─── Flujo 2b: Seguridad ─────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("POST /credentials/issue — rechaza proof con nonce inválido (anti-replay)")
    void rejectInvalidNonce() throws Exception {
        reportSection("Flujo 2b — Seguridad Anti-replay");

        KeyPair holderKeys = DIDKeyUtil.generateSecp256k1KeyPair();
        String  pubHex     = DIDKeyUtil.compressedPublicKeyHex(holderKeys);
        String  did        = DIDKeyUtil.buildDIDKey(pubHex);
        String  kid        = did + "#" + did.substring("did:key:".length());

        long now = System.currentTimeMillis() / 1000;
        String header  = "{\"alg\":\"ES256K\",\"typ\":\"openid4vci-proof+jwt\",\"kid\":\"" + kid + "\"}";
        String payload = "{\"iss\":\"" + did + "\",\"aud\":\"http://localhost\","
            + "\"iat\":" + now + ",\"exp\":" + (now + 300) + ","
            + "\"nonce\":\"nonce-que-nunca-existio\",\"credential_type\":\"TestCredential\"}";

        byte[] privBytes = DIDKeyUtil.to32Bytes(((ECPrivateKey) holderKeys.getPrivate()).getS());
        String proofJwt  = DIDKeyUtil.buildJWT(header, payload, privBytes);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String reqBody = "{\"holder_did\":\"" + did + "\",\"proof\":\"" + proofJwt + "\"}";

        ResponseEntity<Map<String, Object>> resp =
            rest.exchange("/credentials/issue", HttpMethod.POST,
                new HttpEntity<>(reqBody, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        logRequest("POST /credentials/issue — nonce que nunca existió",
                "POST", "/credentials/issue", reqBody,
                resp.getStatusCode().value(), resp.getBody());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        pass("HTTP 400 Bad Request (nonce inválido rechazado)");

        assertThat(resp.getBody().get("error").toString()).containsIgnoringCase("nonce");
        pass("Mensaje de error menciona 'nonce'");

        reportSectionEnd();
    }

    @Test
    @Order(6)
    @DisplayName("POST /credentials/issue — rechaza proof con firma de otra clave")
    void rejectInvalidSignature() throws Exception {
        KeyPair holderKeys   = DIDKeyUtil.generateSecp256k1KeyPair();
        KeyPair attackerKeys = DIDKeyUtil.generateSecp256k1KeyPair();

        String pubHex = DIDKeyUtil.compressedPublicKeyHex(holderKeys);
        String did    = DIDKeyUtil.buildDIDKey(pubHex);
        String kid    = did + "#" + did.substring("did:key:".length());

        // Nonce válido
        ResponseEntity<Map<String, Object>> nonceResp =
            rest.exchange("/credentials/nonce", HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<>() {});
        String nonce = (String) nonceResp.getBody().get("nonce");

        long now = System.currentTimeMillis() / 1000;
        String header  = "{\"alg\":\"ES256K\",\"typ\":\"openid4vci-proof+jwt\",\"kid\":\"" + kid + "\"}";
        String payload = "{\"iss\":\"" + did + "\",\"aud\":\"http://localhost\","
            + "\"iat\":" + now + ",\"exp\":" + (now + 300) + ","
            + "\"nonce\":\"" + nonce + "\",\"credential_type\":\"TestCredential\"}";

        // Firmar con la clave del atacante (no coincide con el DID declarado)
        byte[] attackerPriv = DIDKeyUtil.to32Bytes(((ECPrivateKey) attackerKeys.getPrivate()).getS());
        String proofJwt     = DIDKeyUtil.buildJWT(header, payload, attackerPriv);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String reqBody = "{\"holder_did\":\"" + did + "\",\"proof\":\"" + proofJwt + "\"}";

        ResponseEntity<Map<String, Object>> resp =
            rest.exchange("/credentials/issue", HttpMethod.POST,
                new HttpEntity<>(reqBody, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        logRequest("POST /credentials/issue — proof firmado con clave de atacante",
                "POST", "/credentials/issue", reqBody,
                resp.getStatusCode().value(), resp.getBody());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        pass("HTTP 400 Bad Request (firma inválida rechazada)");

        assertThat(resp.getBody().get("error").toString()).containsIgnoringCase("firma");
        pass("Mensaje de error menciona 'firma'");

        reportSectionEnd();
    }

    // ─── Flujo 3: Metadatos del Holder ───────────────────────────────────────

    @Test
    @Order(7)
    @DisplayName("GET /credentials?holder_did — devuelve metadatos sin exponer el JWT")
    @SuppressWarnings("unchecked")
    void listCredentialMetadata() {
        reportSection("Flujo 3 — Metadatos del Holder");

        assertThat(holderDid).as("El test de emisión (Order 4) debe ejecutarse primero").isNotNull();

        ResponseEntity<List<Map<String, Object>>> resp =
            rest.exchange("/credentials?holder_did=" + holderDid, HttpMethod.GET, null,
                new org.springframework.core.ParameterizedTypeReference<>() {});

        logRequest("GET /credentials?holder_did=" + holderDid,
                "GET", "/credentials?holder_did=" + holderDid, null,
                resp.getStatusCode().value(), resp.getBody());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        pass("HTTP 200 OK");

        assertThat(resp.getBody()).hasSize(1);
        pass("Devuelve exactamente 1 registro");

        Map<String, Object> record = resp.getBody().get(0);

        assertThat(record).containsKey("credential_id");
        pass("Registro contiene 'credential_id'");

        assertThat(record).containsKey("credential_type");
        pass("Registro contiene 'credential_type'");

        assertThat(record).containsKey("issued_at");
        pass("Registro contiene 'issued_at'");

        assertThat(record).containsKey("expires_at");
        pass("Registro contiene 'expires_at'");

        assertThat(record).containsKey("revoked");
        pass("Registro contiene 'revoked'");

        assertThat(record).doesNotContainKey("credential");
        pass("Registro NO expone campo 'credential' (JWT completo ausente)");

        assertThat(record).doesNotContainKey("vc_jwt");
        pass("Registro NO expone campo 'vc_jwt'");

        assertThat(record).doesNotContainKey("jwt");
        pass("Registro NO expone campo 'jwt'");

        reportSectionEnd();
    }
}
