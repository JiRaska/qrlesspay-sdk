// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
package tech.qrlesspay.sdk

/**
 * Short Payment Descriptor (SPD / SPAYD) — Czech/Slovak QR payment standard.
 * Spec: https://en.wikipedia.org/wiki/Short_Payment_Descriptor
 * Format: SPD*1.0*ACC:IBAN+BIC*AM:amount*CC:currency*MSG:message*X-VS:varSymbol
 */
data class SpaydData(
    val iban: String,
    val bic: String? = null,
    val amount: String? = null,
    val currency: String? = null,
    val message: String? = null,
    val varSymbol: String? = null,
    val recipientName: String? = null,
)

/**
 * Parse a SPAYD QR payload into [SpaydData], or return null if not a valid SPAYD string.
 * Tolerates missing optional fields; requires at least ACC: with a non-blank IBAN.
 */
fun parseSpayd(raw: String): SpaydData? {
    val upper = raw.trimEnd('*')
    if (!upper.startsWith("SPD*")) return null
    val parts = upper.split("*")
    if (parts.size < 2) return null
    // parts[0]="SPD", parts[1]="1.0", rest are KEY:VALUE pairs
    val fields = parts.drop(2).mapNotNull { part ->
        val idx = part.indexOf(':')
        if (idx < 1) null else part.substring(0, idx).uppercase() to part.substring(idx + 1)
    }.toMap()

    val accRaw = fields["ACC"] ?: return null
    val (iban, bic) = if ('+' in accRaw) {
        val s = accRaw.split('+', limit = 2)
        s[0] to s[1]
    } else {
        accRaw to null
    }
    if (iban.isBlank()) return null

    return SpaydData(
        iban = iban.trim(),
        bic = bic?.trim(),
        amount = fields["AM"]?.trim(),
        currency = fields["CC"]?.trim(),
        message = fields["MSG"]?.trim(),
        varSymbol = fields["X-VS"]?.trim(),
        recipientName = fields["RN"]?.trim(),
    )
}

/**
 * Normalise an amount to the SPAYD `AM` form: a decimal with exactly two fraction digits and a
 * dot separator (e.g. `450` / `450.0` / `450,5` → `450.00` / `450.00` / `450.50`). Czech bank QR
 * scanners expect `AM:<amount>` as a plain decimal; a stray single-digit fraction or a comma makes
 * some readers reject the code. Falls back to the raw (trimmed) string if it is not a number.
 */
private fun normalizeSpaydAmount(raw: String): String {
    val cents = raw.trim().replace(',', '.').toDoubleOrNull()
        ?.let { kotlin.math.round(it * 100).toLong() }
        ?: return raw.trim()
    return "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}

/** Czech diacritics → ASCII. SPAYD is an ASCII descriptor; the QR-Platba convention is to fold. */
private val CZ_FOLD: Map<Char, Char> = mapOf(
    'á' to 'a', 'č' to 'c', 'ď' to 'd', 'é' to 'e', 'ě' to 'e', 'í' to 'i', 'ň' to 'n', 'ó' to 'o',
    'ř' to 'r', 'š' to 's', 'ť' to 't', 'ú' to 'u', 'ů' to 'u', 'ý' to 'y', 'ž' to 'z',
    'Á' to 'A', 'Č' to 'C', 'Ď' to 'D', 'É' to 'E', 'Ě' to 'E', 'Í' to 'I', 'Ň' to 'N', 'Ó' to 'O',
    'Ř' to 'R', 'Š' to 'S', 'Ť' to 'T', 'Ú' to 'U', 'Ů' to 'U', 'Ý' to 'Y', 'Ž' to 'Z',
)

/** Account number (IBAN/BIC): SPAYD `ACC` must be a contiguous, upper-case token — NO spaces. */
private fun normalizeAccount(raw: String): String = raw.filterNot { it.isWhitespace() }.uppercase()

/** Free-text value (RN/MSG): fold diacritics to ASCII and drop `*` (the SPAYD field delimiter). */
private fun spaydText(raw: String): String =
    buildString { for (c in raw.trim()) append(CZ_FOLD[c] ?: c) }.replace("*", "")

/**
 * Encode [SpaydData] into a spec-valid SPAYD / QR-Platba (ČBA) string that Czech bank apps accept.
 * Every field is normalised here so it does not matter how the caller formatted its inputs — most
 * importantly the IBAN is stripped of the display spaces that otherwise make `ACC` invalid.
 */
fun encodeSpayd(data: SpaydData): String = buildString {
    append("SPD*1.0")
    append("*ACC:${normalizeAccount(data.iban)}")
    if (!data.bic.isNullOrBlank()) append("+${normalizeAccount(data.bic)}")
    if (!data.amount.isNullOrBlank()) append("*AM:${normalizeSpaydAmount(data.amount)}")
    if (!data.currency.isNullOrBlank()) append("*CC:${data.currency.trim().uppercase()}")
    if (!data.recipientName.isNullOrBlank()) append("*RN:${spaydText(data.recipientName)}")
    if (!data.message.isNullOrBlank()) append("*MSG:${spaydText(data.message)}")
    if (!data.varSymbol.isNullOrBlank()) append("*X-VS:${data.varSymbol.filter { it.isDigit() }}")
}
