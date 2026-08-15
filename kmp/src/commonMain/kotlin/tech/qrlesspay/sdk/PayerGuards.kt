// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
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

/*
 * The app this was extracted from keeps a process-wide holder for the log, because accepting a
 * proposal dismisses its nearby sheet and a composable-scoped instance would be discarded exactly
 * when the history becomes useful.
 *
 * That is deliberately NOT part of the SDK. How long the history lives is a host-app decision —
 * a library that installs a global on its consumer's behalf has made a lifetime choice it has no
 * standing to make. Construct a [RecentProposalLog] with your own clock and hold it wherever your
 * navigation model makes it survive.
 */
