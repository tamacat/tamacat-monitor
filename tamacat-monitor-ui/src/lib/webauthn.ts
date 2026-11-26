// Passkeys (WebAuthn) in the browser: the server sends options and expects responses as JSON with
// base64url binaries (WebAuthn Level 3 JSON types); navigator.credentials works with ArrayBuffers.

import { ApiError } from './api';

/* eslint-disable @typescript-eslint/no-explicit-any */

export function passkeysSupported(): boolean {
  return typeof window.PublicKeyCredential === 'function' && !!navigator.credentials;
}

function fromB64url(s: string): ArrayBuffer {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4);
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}

function toB64url(buf: ArrayBuffer | null): string {
  if (!buf) return '';
  const bytes = new Uint8Array(buf);
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

const descriptors = (list: any[] | undefined) =>
  (list ?? []).map((d) => ({ type: 'public-key' as const, id: fromB64url(d.id), transports: d.transports }));

/**
 * Cancelling the browser dialog (or a timeout) is NotAllowedError; it is reported as error.passkey.cancelled.
 * Other browser errors (no authenticator, already registered) as error.passkey.browser.
 */
function browserError(e: unknown): ApiError {
  const name = e instanceof DOMException ? e.name : '';
  if (name === 'NotAllowedError' || name === 'AbortError') return new ApiError(0, 'error.passkey.cancelled');
  if (name === 'InvalidStateError') return new ApiError(0, 'error.passkey.duplicate');
  return new ApiError(0, 'error.passkey.browser');
}

/** navigator.credentials.create with the server's creation options; returns the registration response JSON. */
export async function createPasskey(options: any): Promise<unknown> {
  if (!passkeysSupported()) throw new ApiError(0, 'error.passkey.unsupported');
  const publicKey: PublicKeyCredentialCreationOptions = {
    ...options,
    challenge: fromB64url(options.challenge),
    user: { ...options.user, id: fromB64url(options.user.id) },
    excludeCredentials: descriptors(options.excludeCredentials),
  };
  let cred: PublicKeyCredential;
  try {
    cred = (await navigator.credentials.create({ publicKey })) as PublicKeyCredential;
  } catch (e) {
    throw browserError(e);
  }
  const r = cred.response as AuthenticatorAttestationResponse;
  return {
    id: cred.id,
    rawId: toB64url(cred.rawId),
    type: cred.type,
    response: {
      clientDataJSON: toB64url(r.clientDataJSON),
      attestationObject: toB64url(r.attestationObject),
      transports: typeof r.getTransports === 'function' ? r.getTransports() : [],
    },
  };
}

/** navigator.credentials.get with the server's request options; returns the authentication response JSON. */
export async function getPasskey(options: any): Promise<unknown> {
  if (!passkeysSupported()) throw new ApiError(0, 'error.passkey.unsupported');
  const publicKey: PublicKeyCredentialRequestOptions = {
    ...options,
    challenge: fromB64url(options.challenge),
    allowCredentials: descriptors(options.allowCredentials),
  };
  let cred: PublicKeyCredential;
  try {
    cred = (await navigator.credentials.get({ publicKey })) as PublicKeyCredential;
  } catch (e) {
    throw browserError(e);
  }
  const r = cred.response as AuthenticatorAssertionResponse;
  return {
    id: cred.id,
    rawId: toB64url(cred.rawId),
    type: cred.type,
    response: {
      clientDataJSON: toB64url(r.clientDataJSON),
      authenticatorData: toB64url(r.authenticatorData),
      signature: toB64url(r.signature),
      userHandle: toB64url(r.userHandle),
    },
  };
}
