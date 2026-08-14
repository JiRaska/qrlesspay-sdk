// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
package tech.qrlesspay.sdk



/**
 * Display names that more than one visible tile is currently advertising.
 *
 * The advert carries a first name and nothing else identifying (spec §2: the IBAN is never on the
 * air), so two people called Jiří look identical in the list — and so does an attacker who
 * advertises a victim's first name with their own IBAN. The name is not an authenticator, and with
 * bank attestation out of the protocol (spec §11) and VOP absent on the CZ domestic rail, telling
 * the payer that the choice is ambiguous is the control that remains on this axis.
 *
 * Comparison is case- and whitespace-insensitive: "jiri" and "Jiri " are the same name to a person
 * glancing at a list, and an impersonator would pick the spelling that reads the same.
 */
fun ambiguousDisplayNames(tiles: List<NearbyTile>): Set<String> = tiles.groupBy { it.firstName.trim().lowercase() }
    .filterValues { it.size > 1 }
    .keys

/**
 * Device-local memory of QRlessPay requests this phone has recently turned into a payment proposal.
 *
 * Complements the single-use [ReplayGuard], which rejects the *same bundle* twice. This catches the
 * next case out: the payee re-opens their request screen, so a genuinely new bundle arrives — new
 * `sid`, new `nonce`, valid signature — carrying the payment the payer has already made. Nothing in
 * the protocol can tell those apart, because from the wire's point of view the second one is simply
 * a fresh request.
 *
 * Keyed on (IBAN, amount) because that is what "the same payment" means to a person. Device-local
 * for the same reason as everything else here: there is no server in this protocol (spec §11), so
 * the only history available is this phone's own.
 */
class RecentProposalLog(
    private val now: () -> Long,
    private val windowSec: Long = DEFAULT_WINDOW_SEC,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val accepted = mutableMapOf<String, Long>()

    /** Records that a proposal for this (IBAN, amount) was opened. */
    fun record(iban: String, amountMinor: Int) {
        prune()
        if (accepted.size >= maxEntries) {
            accepted.entries.minByOrNull { it.value }?.let { accepted.remove(it.key) }
        }
        accepted[key(iban, amountMinor)] = now()
    }

    /**
     * Seconds since an identical proposal was opened on this device, or `null` if there was none
     * inside the window. The caller decides what to do with it — this deliberately does not veto,
     * because paying the same person the same amount twice is a thing people legitimately do.
     */
    fun secondsSinceIdentical(iban: String, amountMinor: Int): Long? {
        prune()
        return accepted[key(iban, amountMinor)]?.let { now() - it }
    }

    internal fun size(): Int = accepted.size

    private fun prune() {
        val cutoff = now() - windowSec
        accepted.entries.removeAll { it.value < cutoff }
    }

    private fun key(iban: String, amountMinor: Int) = "${iban.trim().uppercase()}|$amountMinor"

    companion object {
        /**
         * Long enough to cover "did I already pay for this round?", short enough that tomorrow's
         * identical rent transfer is not second-guessed.
         */
        const val DEFAULT_WINDOW_SEC = 15L * 60L
        const val DEFAULT_MAX_ENTRIES = 64
    }
}

/**
 * Process-wide holder for the payer's own recent-proposal history.
 *
 * A global is not the shape one would pick freely, and it is the shape this needs: accepting a
 * proposal dismisses the nearby sheet and navigates to the send screen, so anything held by
 * `remember` is discarded at exactly the moment the history becomes worth having. The window this
 * guards — coming back and tapping the same person again — is precisely the one a composable-scoped
 * log cannot see. The log itself takes an injected clock and is tested directly; this object only
 * decides how long it lives.
 */
object NearPayPayerMemory {
    val recentProposals = RecentProposalLog(now = ::nowEpochSeconds)
}
