// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
package tech.qrlesspay.sdk

/**
 * SPAYD-URI — a SPAYD descriptor carried as a URI, so the phone's own camera hands it to a bank app.
 *
 * A bare `SPD*1.0*…` string is text to every OS camera: it is shown, never offered to an app. Only a
 * URI reaches the app-chooser path, so the camera-scannable form is the same descriptor behind a
 * registered scheme:
 *
 * ```
 * spayd://v1/SPD*1.0*ACC:CZ6508000000192000145399*AM:250.00*CC:CZK*MSG:Faktura%20123
 * ```
 *
 * Two schemes, spec: `SPAYD-URI.md` at the repository root.
 *  - `spayd` — the common scheme every bank registers. On Android, several registrants give the
 *    system's own chooser; on iOS the OS opens exactly one of them, which is why the second exists.
 *  - `spayd-<bank code>` — the per-bank relay scheme (`spayd-0800`, `spayd-0300`, …). The app iOS
 *    picked for `spayd` lists the *other* installed banks (`canOpenURL` on each relay scheme) and
 *    forwards the identical payload to the one the payer chooses. Android apps register it too, so
 *    one relay implementation serves both platforms.
 *
 * The payload is the SPAYD verbatim, percent-encoded only where a URI forbids the byte. `*`, `:`,
 * `+` and `,` are legal path characters, so a typical descriptor grows by nothing but the prefix.
 *
 * **This changes nothing about what a SPAYD is.** No backend, no lookup, no registry — the
 * invariant of spec §11 holds because the URI is unwrapped on the device and the bytes inside it
 * are exactly what a printed QR would carry. [unwrap] therefore accepts both forms, so a scanner
 * migrated to it keeps reading every QR already printed.
 */
object SpaydUri {

    /** The common scheme; every bank app registers it. */
    const val SCHEME: String = "spayd"

    /** The envelope version segment after the authority slashes; bumps only if the encoding does. */
    const val VERSION_SEGMENT: String = "v1"

    private const val SPAYD_PREFIX = "SPD*"

    /**
     * The domain-verified envelope. A custom scheme can be squatted by any app, and the OS will
     * hand it every camera scan; a universal / app link can only be opened by apps listed in the
     * domain's association files, which the operator of [LINK_HOST] controls. The association
     * files are cached on the device at install time, so an installed bank opens the link
     * offline; only a phone with no bank at all reaches the web page behind it.
     *
     * `https://open-bank.tech/pay/v1?SPD*…` is the common link, `…/pay/v1/<bank code>?SPD*…` one
     * bank's. Pilot host; the standard's host is for the association to choose.
     */
    const val LINK_HOST: String = "open-bank.tech"
    const val LINK_PATH: String = "/pay/v1"

    /** Which envelope carried the descriptor. */
    enum class Envelope { SCHEME, LINK }

    /** Where a URI asks to be opened: the common scheme, or one bank's relay scheme. */
    data class Target(val bankCode: String?) {
        /** The scheme this target is addressed by, lower case. */
        val scheme: String get() = if (bankCode == null) SCHEME else "$SCHEME-$bankCode"
    }

    /** A successfully unwrapped payload: the SPAYD string, the target it was addressed to, and how. */
    data class Unwrapped(val spayd: String, val target: Target, val envelope: Envelope = Envelope.SCHEME)

    /** Why [parse] refused. Asserted by the conformance corpus — the reason is part of the contract. */
    enum class Rejection {
        /** Not addressed to `spayd` or `spayd-NNNN` at all. */
        NOT_SPAYD_URI,

        /** `spayd-` followed by something other than exactly four digits. */
        BAD_BANK_CODE,

        /** `spayd://x/…` where `x` is neither `v1` nor the descriptor itself. */
        BAD_VERSION,

        /** A `%` not followed by two hex digits, or decoded bytes that are not UTF-8. */
        BAD_PERCENT_ENCODING,

        /** A raw byte a URI forbids (space, control, non-ASCII), or a control character smuggled in by an escape. */
        ILLEGAL_CHARACTER,

        /** Decoded cleanly and is not a SPAYD (does not start with `SPD*`, or [parseSpayd] refuses it). */
        NOT_SPAYD,

        /** An https link whose host is not [LINK_HOST]: not ours, whatever its path says. */
        BAD_LINK_HOST,
    }

    sealed class Result {
        data class Ok(val value: Unwrapped) : Result()
        data class Rejected(val reason: Rejection) : Result()
    }

    /**
     * Wrap a SPAYD as a URI. [bankCode] selects the relay scheme (`spayd-0800://…`) and must be
     * exactly four digits; null gives the common scheme a QR code should carry.
     *
     * The descriptor is encoded, never repaired: pass the output of [encodeSpayd] (or any string
     * [parseSpayd] accepts). A string that is not a SPAYD is refused here rather than wrapped into
     * something every receiver will reject.
     */
    fun wrap(spayd: String, bankCode: String? = null): String {
        require(parseSpayd(spayd) != null) { "not a SPAYD descriptor" }
        require(bankCode == null || isBankCode(bankCode)) { "bank code must be exactly four digits" }
        return Target(bankCode).scheme + "://" + VERSION_SEGMENT + "/" + percentEncode(spayd)
    }

    /**
     * Wrap a SPAYD as a domain-verified link — the form a QR should carry where the link host's
     * association files are deployed. [bankCode] addresses one bank's path; null is the common link.
     */
    fun wrapLink(spayd: String, bankCode: String? = null): String {
        require(parseSpayd(spayd) != null) { "not a SPAYD descriptor" }
        require(bankCode == null || isBankCode(bankCode)) { "bank code must be exactly four digits" }
        val path = if (bankCode == null) LINK_PATH else "$LINK_PATH/$bankCode"
        return "https://" + LINK_HOST + path + "?" + percentEncode(spayd, forQuery = true)
    }

    /**
     * Accept a scanned payload in either form — bare `SPD*…` or a SPAYD-URI — and return the SPAYD.
     *
     * This is the one call a QR scanner should make. A bare descriptor passes through untouched
     * (after [parseSpayd] agrees it is one), so adopting it is non-breaking for every code already
     * printed. Returns null for anything else; use [parse] when the rejection reason matters.
     */
    fun unwrap(raw: String): String? {
        val s = raw.trim()
        if (s.startsWith(SPAYD_PREFIX)) return if (parseSpayd(s) != null) s else null
        return (parse(s) as? Result.Ok)?.value?.spayd
    }

    /** Strict parse of a SPAYD-URI with the reason on refusal. A bare `SPD*…` is [Rejection.NOT_SPAYD_URI]. */
    fun parse(uri: String): Result {
        val s = uri.trim()
        val colon = s.indexOf(':')
        if (colon < 0) return Result.Rejected(Rejection.NOT_SPAYD_URI)
        val scheme = s.substring(0, colon).lowercase()
        if (scheme == "https") return parseLink(s.substring(colon + 1))
        val target = when {
            scheme == SCHEME -> Target(null)
            scheme.startsWith("$SCHEME-") -> {
                val code = scheme.substring(SCHEME.length + 1)
                if (!isBankCode(code)) return Result.Rejected(Rejection.BAD_BANK_CODE)
                Target(code)
            }
            else -> return Result.Rejected(Rejection.NOT_SPAYD_URI)
        }

        var rest = s.substring(colon + 1)
        // Tolerated on input, never produced: `spayd:SPD*…` (opaque, mailto-style) and
        // `spayd://SPD*…` (no version segment). Some QR producers will write them; a payer should
        // still be able to pay.
        if (rest.startsWith("//")) {
            rest = rest.substring(2)
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                val head = rest.substring(0, slash)
                if (head.equals(VERSION_SEGMENT, ignoreCase = true)) {
                    rest = rest.substring(slash + 1)
                } else if (!head.startsWith(SPAYD_PREFIX, ignoreCase = true)) {
                    return Result.Rejected(Rejection.BAD_VERSION)
                }
            } else if (!rest.startsWith(SPAYD_PREFIX, ignoreCase = true)) {
                return Result.Rejected(Rejection.BAD_VERSION)
            }
        }

        return decodePayload(rest, target, Envelope.SCHEME)
    }

    /** `//host/pay/v1[/code]?payload[#…]`, the part after `https:`. Host case-insensitive, path exact. */
    private fun parseLink(afterScheme: String): Result {
        if (!afterScheme.startsWith("//")) return Result.Rejected(Rejection.NOT_SPAYD_URI)
        val noFragment = afterScheme.substringBefore('#')
        val authorityAndPath = noFragment.substringBefore('?')
        val query = if ('?' in noFragment) noFragment.substringAfter('?') else ""
        val hostEnd = authorityAndPath.indexOf('/', 2).let { if (it < 0) authorityAndPath.length else it }
        val host = authorityAndPath.substring(2, hostEnd).lowercase()
        if (host != LINK_HOST) return Result.Rejected(Rejection.BAD_LINK_HOST)
        val path = authorityAndPath.substring(hostEnd).trimEnd('/')
        val target = when {
            path == LINK_PATH -> Target(null)
            path.startsWith("$LINK_PATH/") -> {
                val code = path.substring(LINK_PATH.length + 1)
                if (!isBankCode(code)) return Result.Rejected(Rejection.BAD_BANK_CODE)
                Target(code)
            }
            else -> return Result.Rejected(Rejection.BAD_VERSION)
        }
        return decodePayload(query, target, Envelope.LINK)
    }

    private fun decodePayload(encoded: String, target: Target, envelope: Envelope): Result {
        for (c in encoded) {
            if (c.code <= 0x20 || c.code >= 0x7F) return Result.Rejected(Rejection.ILLEGAL_CHARACTER)
        }
        val decoded = percentDecode(encoded) ?: return Result.Rejected(Rejection.BAD_PERCENT_ENCODING)
        // An escape can smuggle in what a raw byte could not. A control character has no place in a
        // SPAYD (NUL in particular: no database column accepts it), and refusing it is the same
        // decision as refusing the raw byte.
        if (decoded.any { it.code < 0x20 || it.code == 0x7F }) return Result.Rejected(Rejection.ILLEGAL_CHARACTER)
        if (!decoded.startsWith(SPAYD_PREFIX) || parseSpayd(decoded) == null) {
            return Result.Rejected(Rejection.NOT_SPAYD)
        }
        return Result.Ok(Unwrapped(decoded, target, envelope))
    }

    /**
     * The relay scheme for a bank code — what a relay app probes with `canOpenURL` / `queryIntentActivities`.
     * Null for an invalid code, so a hand-maintained bank list cannot produce a scheme nobody registered.
     */
    fun relayScheme(bankCode: String): String? = if (isBankCode(bankCode)) "$SCHEME-$bankCode" else null

    fun isBankCode(code: String): Boolean = code.length == 4 && code.all { it in '0'..'9' }

    // Unreserved (RFC 3986 §2.3) plus the sub-delims a SPAYD actually uses and a path segment permits.
    // `%` must be encoded (it would read as an escape), `/` must be (it ends the segment),
    // `?` and `#` must be (query and fragment delimiters). Everything non-ASCII is encoded as UTF-8.
    // In a query string `+` is read as a space by many form decoders, so the link form encodes it;
    // a path segment has no such convention and keeps it raw.
    private fun isRaw(c: Char, forQuery: Boolean): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' ||
            c == '~' || c == '*' || c == ':' || c == ',' || (c == '+' && !forQuery)

    private const val HEX = "0123456789ABCDEF"

    internal fun percentEncode(s: String, forQuery: Boolean = false): String = buildString {
        for (b in s.encodeToByteArray()) {
            val v = b.toInt() and 0xFF
            if (v < 0x80 && isRaw(v.toChar(), forQuery)) {
                append(v.toChar())
            } else {
                append('%'); append(HEX[v shr 4]); append(HEX[v and 0x0F])
            }
        }
    }

    /** Null on a malformed escape or on bytes that are not valid UTF-8. */
    internal fun percentDecode(s: String): String? {
        val out = ByteArray(s.length)
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = s[i + 1].digitToIntOrNull(16) ?: return null
                val lo = s[i + 2].digitToIntOrNull(16) ?: return null
                out[n++] = ((hi shl 4) or lo).toByte()
                i += 3
            } else {
                out[n++] = c.code.toByte()
                i++
            }
        }
        val bytes = out.copyOf(n)
        val decoded = bytes.decodeToString()
        // decodeToString substitutes U+FFFD for invalid sequences instead of throwing; re-encoding
        // and comparing is the portable way to detect that it did.
        return if (decoded.encodeToByteArray().contentEquals(bytes)) decoded else null
    }
}
