package com.flowglass.demo.xposed.method

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 over the exact UTF-8 body string. No token is ever logged. */
internal object MethodHookHmac {
    private const val ALGORITHM = "HmacSHA256"

    fun sign(token: String, body: String): String {
        require(token.isNotBlank()) { "token must not be blank" }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), ALGORITHM))
        return Base64.encodeToString(
            mac.doFinal(body.toByteArray(Charsets.UTF_8)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    fun verify(token: String, body: String, expected: String): Boolean {
        if (token.isBlank() || expected.isBlank()) return false
        return runCatching {
            MessageDigest.isEqual(
                sign(token, body).toByteArray(Charsets.UTF_8),
                expected.toByteArray(Charsets.UTF_8),
            )
        }.getOrDefault(false)
    }
}
