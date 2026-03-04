package com.did.wallet.util;

import java.util.Arrays;

/**
 * Implementación Base58 (Bitcoin alphabet) para encoding multibase de did:key.
 */
public final class Base58 {

    private static final char[] ALPHABET =
        "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    private static final int[] INDEXES = new int[128];

    static {
        Arrays.fill(INDEXES, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            INDEXES[ALPHABET[i]] = i;
        }
    }

    private Base58() {}

    public static String encode(byte[] input) {
        if (input.length == 0) return "";

        // Contar ceros iniciales
        int leadingZeros = 0;
        while (leadingZeros < input.length && input[leadingZeros] == 0) leadingZeros++;

        byte[] copy = Arrays.copyOf(input, input.length);
        char[] output = new char[copy.length * 2];
        int outputStart = output.length;

        for (int inputStart = leadingZeros; inputStart < copy.length; ) {
            output[--outputStart] = ALPHABET[divmod(copy, inputStart, 256, 58)];
            if (copy[inputStart] == 0) inputStart++;
        }

        while (outputStart < output.length && output[outputStart] == ALPHABET[0]) outputStart++;
        while (leadingZeros-- > 0) output[--outputStart] = ALPHABET[0];

        return new String(output, outputStart, output.length - outputStart);
    }

    public static byte[] decode(String input) {
        if (input.isEmpty()) return new byte[0];

        byte[] input58 = new byte[input.length()];
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int digit = (c < 128) ? INDEXES[c] : -1;
            if (digit < 0) throw new IllegalArgumentException("Invalid Base58 char: " + c);
            input58[i] = (byte) digit;
        }

        int leadingZeros = 0;
        while (leadingZeros < input58.length && input58[leadingZeros] == 0) leadingZeros++;

        byte[] decoded = new byte[input.length()];
        int outputStart = decoded.length;

        for (int inputStart = leadingZeros; inputStart < input58.length; ) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256);
            if (input58[inputStart] == 0) inputStart++;
        }

        while (outputStart < decoded.length && decoded[outputStart] == 0) outputStart++;

        return Arrays.copyOfRange(decoded, outputStart - leadingZeros, decoded.length);
    }

    private static byte divmod(byte[] number, int firstDigit, int base, int divisor) {
        int remainder = 0;
        for (int i = firstDigit; i < number.length; i++) {
            int digit = (number[i] & 0xFF);
            int temp = remainder * base + digit;
            number[i] = (byte) (temp / divisor);
            remainder = temp % divisor;
        }
        return (byte) remainder;
    }
}
