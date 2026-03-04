package com.did.wallet.util;

import android.util.Base64;

/** Base64url sin padding (RFC 7515). */
public final class Base64Url {

    private Base64Url() {}

    public static String encode(byte[] data) {
        return Base64.encodeToString(data, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    public static byte[] decode(String s) {
        return Base64.decode(s, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
