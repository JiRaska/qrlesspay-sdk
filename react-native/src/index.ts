// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors.

/**
 * React Native binding for QRlessPay.
 *
 * This layer is deliberately thin and does **no verification of its own**. Every security decision
 * — the signature, the advert↔bundle binding, expiry, single use, the proximity gate — happens in
 * the native SDKs, which are the implementations the conformance suite covers. A binding that
 * re-implemented any of it in TypeScript would be a third dialect nobody tests, and the one most
 * likely to be wrong, since JavaScript is the layer an adopter is most tempted to patch.
 *
 * What crosses the bridge is therefore a *result*, never a bundle to be checked on this side.
 */

import { NativeEventEmitter, NativeModules } from 'react-native'

const LINKING_ERROR =
  "The '@qrlesspay/react-native' native module is not linked. Rebuild the app after installing " +
  '(pod install for iOS); it cannot be used in Expo Go, which has no custom native code.'

type NativeQRlessPay = {
  startReceiving(firstName: string, spayd: string, amountMinor: number | null): Promise<void>
  stopReceiving(): Promise<void>
  startDiscovery(): Promise<void>
  stopDiscovery(): Promise<void>
  resolve(peerId: string): Promise<NativeResolveResult>
}

type NativeResolveResult = { ok: true; spayd: string } | { ok: false; reason: string }

const Native: NativeQRlessPay = NativeModules.QRlessPay
  ? NativeModules.QRlessPay
  : new Proxy({} as NativeQRlessPay, {
      get() {
        throw new Error(LINKING_ERROR)
      },
    })

/**
 * A nearby payee, as the advert describes itself.
 *
 * `firstName` and `amountMinor` are **display hints from an unauthenticated broadcast**. They are
 * not authority and must never be shown as the payment's recipient or amount — those come from
 * {@link resolve}, out of the signed bundle. The types keep them apart so a screen cannot confuse
 * the two by accident.
 */
export type NearbyTile = {
  peerId: string
  firstName: string
  amountMinor: number | null
  rssi: number
}

/** The verified payment proposal. This, and only this, may be shown to the payer. */
export type VerifiedProposal = { spayd: string }

/**
 * Why a tap did not produce a proposal. A stable machine code, not display copy — the host app
 * owns the wording, in its own tone and language.
 *
 * `replayed` deserves separate handling: it means this device already accepted that exact bundle,
 * so the honest message is "you already paid this", never "try again". Telling a payer to retry
 * after a successful payment invites them to pay twice.
 */
export type RejectionReason =
  | 'bad-advert'
  | 'fetch-failed'
  | 'bad-bundle'
  | 'version'
  | 'field-size'
  | 'key-or-sig-size'
  | 'sid-mismatch'
  | 'advert-bundle-binding'
  | 'expired'
  | 'exp-too-far'
  | 'bad-signature'
  | 'bad-spayd'
  | 'replayed'

export type ResolveResult =
  | { verified: true; proposal: VerifiedProposal }
  | { verified: false; reason: RejectionReason }

const emitter = NativeModules.QRlessPay ? new NativeEventEmitter(NativeModules.QRlessPay) : null

/**
 * Start advertising a payment request. Runs only while the request screen is open — the caller
 * must {@link stopReceiving} on dispose, since a beacon left running broadcasts a name after the
 * user believes they have closed the screen.
 *
 * iOS allows one active advertisement at a time: a host app already advertising something else
 * must stop it first.
 */
export function startReceiving(args: {
  firstName: string
  spayd: string
  amountMinor?: number | null
}): Promise<void> {
  return Native.startReceiving(args.firstName, args.spayd, args.amountMinor ?? null)
}

export function stopReceiving(): Promise<void> {
  return Native.stopReceiving()
}

/**
 * Subscribe to nearby payees. Tiles arrive already filtered by the proximity gate and ordered
 * strongest-signal-first by the native SDK — this binding does not re-sort or re-filter them,
 * because a gate applied twice in two places is a gate that can disagree with itself.
 *
 * Returns an unsubscribe function; call it when the screen goes away.
 */
export function observeNearby(onTiles: (tiles: NearbyTile[]) => void): () => void {
  if (!emitter) throw new Error(LINKING_ERROR)
  // The emitter's signature is `(...args: Object[])`, so the cast is where the bridge's untyped
  // boundary is acknowledged rather than hidden behind `any` sprinkled through the call site.
  const subscription = emitter.addListener('qrlesspay:tiles', (payload) =>
    onTiles(payload as unknown as NearbyTile[]),
  )
  void Native.startDiscovery()
  return () => {
    subscription.remove()
    void Native.stopDiscovery()
  }
}

/**
 * Fetch and verify the selected payee's signed bundle.
 *
 * On success the host app must still show its own confirmation screen with the recipient and
 * masked IBAN, and run SCA. The SDK stops at the proposal on purpose: no money moves in this
 * protocol, and the payer's explicit confirmation is the authorising act.
 */
export async function resolve(tile: NearbyTile): Promise<ResolveResult> {
  const result = await Native.resolve(tile.peerId)
  return result.ok
    ? { verified: true, proposal: { spayd: result.spayd } }
    : { verified: false, reason: result.reason as RejectionReason }
}

/**
 * Display names shown by more than one visible tile.
 *
 * The advert carries a first name and nothing else identifying — the IBAN is never broadcast — so
 * two people called Jiří are indistinguishable in the list, and so is someone advertising a
 * victim's name with their own account. Warn before the payer picks; the list cannot resolve it
 * for them.
 */
export function ambiguousDisplayNames(tiles: NearbyTile[]): string[] {
  const counts = new Map<string, number>()
  for (const tile of tiles) {
    const key = tile.firstName.trim().toLowerCase()
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  return [...counts.entries()].filter(([, n]) => n > 1).map(([name]) => name)
}
