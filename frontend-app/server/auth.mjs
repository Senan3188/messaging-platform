import { createHash } from 'node:crypto';
import * as oidc from 'openid-client';
import { EncryptJWT, jwtDecrypt } from 'jose';

const appUrl = new URL(process.env.APP_ORIGIN);
export const appOrigin = appUrl.origin;
const issuer = new URL(process.env.OIDC_ISSUER_URI);
const backchannel = new URL(process.env.OIDC_BACKCHANNEL_URI || issuer);
const secure = appUrl.protocol === 'https:';
const loopback = ['localhost', '127.0.0.1', '[::1]'].includes(appUrl.hostname);
if ((!secure && !(appUrl.protocol === 'http:' && loopback)) ||
    (secure && (issuer.protocol !== 'https:' || backchannel.protocol !== 'https:'))) {
  throw new Error('Use HTTPS for the application and Keycloak outside loopback development');
}
const key = Buffer.from(process.env.COOKIE_ENCRYPTION_KEY ?? '', 'hex');
if (!/^[a-fA-F0-9]{64}$/.test(process.env.COOKIE_ENCRYPTION_KEY ?? '') || key.length !== 32) {
  throw new Error('COOKIE_ENCRYPTION_KEY must contain 32 random bytes encoded as hex');
}

const clientId = process.env.OIDC_CLIENT_ID || 'messaging-client';
const clientSecret = process.env.OIDC_CLIENT_SECRET;
const endpoint = (base, name) => base.href.replace(/\/$/, '') + '/protocol/openid-connect/' + name;
const config = new oidc.Configuration({
  issuer: issuer.href,
  authorization_endpoint: endpoint(issuer, 'auth'),
  token_endpoint: endpoint(backchannel, 'token'),
  jwks_uri: endpoint(backchannel, 'certs'),
  revocation_endpoint: endpoint(backchannel, 'revoke'),
}, clientId, clientSecret, clientSecret ? oidc.ClientSecretBasic(clientSecret) : oidc.None());
config.timeout = 8;
if (!secure) oidc.allowInsecureRequests(config);
oidc.enableNonRepudiationChecks(config);

const cookieOptions = purpose => ({
  httpOnly: true,
  secure,
  sameSite: 'lax',
  path: secure ? '/' : purpose === 'flow' ? '/callback' : '/api',
});
const name = purpose => (secure ? '__Host-' : '') + 'relay_' + purpose;
const redirectUri = appOrigin + '/callback';
const now = () => Math.floor(Date.now() / 1000);

async function sealCookie(purpose, value, expiry) {
  const encrypted = await new EncryptJWT(value)
    .setProtectedHeader({ alg: 'dir', enc: 'A256GCM' })
    .setAudience(purpose)
    .setExpirationTime(expiry)
    .encrypt(key);
  if (encrypted.length > 3750) throw new Error('Encrypted browser cookie exceeds its size budget');
  return { purpose, encrypted, expiry };
}

function writeCookie(res, { purpose, encrypted, expiry }) {
  res.cookie(name(purpose), encrypted, { ...cookieOptions(purpose), maxAge: Math.max(0, expiry - now()) * 1000 });
}

async function readCookie(req, purpose) {
  const value = req.cookies[name(purpose)];
  if (!value) return null;
  try {
    const { payload } = await jwtDecrypt(value, key, {
      audience: purpose,
      keyManagementAlgorithms: ['dir'],
      contentEncryptionAlgorithms: ['A256GCM'],
      requiredClaims: ['exp'],
    });
    return payload;
  } catch {
    return null;
  }
}

function clearCookie(res, purpose) {
  res.clearCookie(name(purpose), cookieOptions(purpose));
}

async function saveTokens(res, tokens, previousRefresh) {
  const accessExpiry = now() + (tokens.expires_in ?? 300);
  const refreshExpiry = tokens.refresh_expires_in === undefined
    ? previousRefresh?.exp ?? now() + 1800
    : now() + tokens.refresh_expires_in;
  const refresh = tokens.refresh_token ?? previousRefresh?.token;
  const accessCookie = await sealCookie('access', { token: tokens.access_token }, accessExpiry);
  const refreshCookie = refresh && refreshExpiry > now()
    ? await sealCookie('refresh', { token: refresh }, refreshExpiry)
    : null;
  writeCookie(res, accessCookie);
  if (refreshCookie) writeCookie(res, refreshCookie);
  else clearCookie(res, 'refresh');
}

export async function login(req, res) {
  const verifier = oidc.randomPKCECodeVerifier();
  const state = oidc.randomState();
  const nonce = oidc.randomNonce();
  writeCookie(res, await sealCookie('flow', { verifier, state, nonce }, now() + 600));
  const url = oidc.buildAuthorizationUrl(config, {
    redirect_uri: redirectUri,
    scope: 'openid',
    state,
    nonce,
    code_challenge: await oidc.calculatePKCECodeChallenge(verifier),
    code_challenge_method: 'S256',
    prompt: 'login',
  });
  res.redirect(url.href);
}

export async function callback(req, res) {
  const flow = await readCookie(req, 'flow');
  clearCookie(res, 'flow');
  if (!flow) return res.redirect('/?auth=expired');
  try {
    const url = new URL(redirectUri);
    url.search = new URL(req.originalUrl, appOrigin).search;
    const tokens = await oidc.authorizationCodeGrant(config, url, {
      pkceCodeVerifier: flow.verifier,
      expectedState: flow.state,
      expectedNonce: flow.nonce,
      idTokenExpected: true,
    });
    await saveTokens(res, tokens, null);
    res.redirect('/');
  } catch (error) {
    console.warn('OIDC callback failed:', error.code ?? error.name);
    res.redirect('/?auth=failed');
  }
}

const refreshes = new Map();
const refreshKey = value => createHash('sha256').update(value).digest('base64url');

export async function accessToken(req, res) {
  const access = await readCookie(req, 'access');
  if (access?.exp > now() + 5) return access.token;

  const refresh = await readCookie(req, 'refresh');
  if (!refresh) return access?.token ?? null;
  const id = refreshKey(refresh.token);
  let pending = refreshes.get(id);
  if (!pending) {
    pending = oidc.refreshTokenGrant(config, refresh.token);
    refreshes.set(id, pending);
    const cleanup = () => setTimeout(() => refreshes.delete(id), 5000).unref();
    pending.then(cleanup, cleanup);
  }
  try {
    const tokens = await pending;
    await saveTokens(res, tokens, refresh);
    return tokens.access_token;
  } catch (error) {
    if (error.error !== 'invalid_grant') throw error;
    clearCookie(res, 'access');
    clearCookie(res, 'refresh');
    return null;
  }
}

export async function logout(req, res) {
  const refresh = await readCookie(req, 'refresh');
  clearCookie(res, 'flow');
  clearCookie(res, 'access');
  clearCookie(res, 'refresh');
  if (refresh) {
    try {
      const id = refreshKey(refresh.token);
      const pending = refreshes.get(id);
      refreshes.delete(id);
      const latest = pending ? await pending.catch(() => null) : null;
      await oidc.tokenRevocation(config, latest?.refresh_token ?? refresh.token, { token_type_hint: 'refresh_token' });
    } catch (error) {
      console.warn('OIDC revocation unavailable:', error.code ?? error.name);
    }
  }
  res.sendStatus(204);
}
