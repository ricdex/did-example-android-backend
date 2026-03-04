package com.did.issuer.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestiona nonces de un solo uso para proteger contra ataques de replay.
 *
 * Un nonce debe:
 *  1. Ser entregado al cliente justo antes de que firme el proof.
 *  2. Ser consumido (eliminado) en el momento de verificación.
 *  3. Expirar si no se usa en el tiempo configurado.
 */
@Service
public class NonceService {

    private final SecureRandom rng = new SecureRandom();

    @Value("${issuer.nonce-ttl-seconds:300}")
    private long nonceTtlSeconds;

    // nonce → timestamp de creación (ms)
    private final ConcurrentHashMap<String, Long> store = new ConcurrentHashMap<>();

    /** Genera y registra un nonce criptográficamente seguro. */
    public String generate() {
        byte[] bytes = new byte[24];
        rng.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.put(nonce, System.currentTimeMillis());
        evictExpired();
        return nonce;
    }

    /**
     * Verifica y consume el nonce (single-use).
     *
     * @return true si el nonce es válido y no ha expirado
     */
    public boolean consumeIfValid(String nonce) {
        Long created = store.remove(nonce);
        if (created == null) return false;
        long ageMs = System.currentTimeMillis() - created;
        return ageMs <= nonceTtlSeconds * 1000;
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now - e.getValue() > nonceTtlSeconds * 1000);
    }
}
