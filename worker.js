/**
 * V Vault account server  —  Cloudflare Worker + D1 (SQLite)
 *
 * What it does: keeps track of which phone is the ACTIVE one for a Google
 * account, and lets a second phone sign in only after the first phone approves.
 *
 *   - Nothing active (never signed in, signed out, or idle for a long time):
 *     a phone signs in straight away, no popup.
 *   - Another phone is active: the new phone waits, the active phone shows an
 *     Approve / Deny popup. Approve moves the account to the new phone.
 *
 * What it stores (per Google email): the active device id, a HASH of that
 * device's session secret, and a last-seen time. Plus short-lived sign-in
 * requests. No photos, no passwords, no Google tokens (a token is only used to
 * ask Google "whose is this?" and is never stored or logged).
 *
 * Setup: see SETUP-server.md. Bind a D1 database to this Worker with the
 * variable name  DB.  Tables are created automatically on first request.
 */

const DEFAULTS = {
  IDLE_MS: 14 * 24 * 3600 * 1000,        // active phone silent this long => account counts as free
  REQUEST_TTL_MS: 30 * 60 * 1000,        // how long a sign-in request stays open
  MAX_REQUESTS_PER_HOUR: 5,              // stops someone spamming the active phone with popups
  WRITE_THROTTLE_MS: 5 * 60 * 1000       // last-seen is written at most this often (saves D1 writes)
};

const te = new TextEncoder();
const ID_RE = /^[A-Za-z0-9_-]{8,64}$/;
const HEX_RE = /^[a-f0-9]{32,128}$/;

function toHex(bytes) {
  return Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2, '0')).join('');
}
export async function sha256Hex(s) {
  return toHex(await crypto.subtle.digest('SHA-256', te.encode(String(s))));
}
function newId() {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  return toHex(b);
}
function cleanName(s) {
  return String(s || 'Another phone').replace(/[\u0000-\u001f\u007f<>]/g, '').trim().slice(0, 60) || 'Another phone';
}

/* ------------------------------------------------------------------ */
/* Core rules (storage-agnostic, so they can be tested without Cloudflare) */
/* ------------------------------------------------------------------ */
export function createApi({ store, verifyToken, now = () => Date.now(), config = {} }) {
  const cfg = { ...DEFAULTS, ...config };
  const fail = (status, error) => ({ status, body: { ok: false, error } });

  async function checkSession(emailRaw, deviceId, session) {
    const email = String(emailRaw || '').toLowerCase();
    if (!email || !ID_RE.test(String(deviceId || '')) || typeof session !== 'string' || !session) return null;
    const acct = await store.getAccount(email);
    if (!acct || !acct.device_id || acct.device_id !== deviceId) return null;
    if (acct.session_hash !== await sha256Hex(session)) return null;
    return { acct, email };
  }

  return {
    /** A phone asks to become the active phone for the Google account behind googleToken. */
    async login(b) {
      const deviceId = String(b.deviceId || '');
      if (!ID_RE.test(deviceId) || !HEX_RE.test(String(b.sessionHash || '')) || !HEX_RE.test(String(b.pollHash || ''))) {
        return fail(400, 'bad_request');
      }
      const email = await verifyToken(String(b.googleToken || ''));
      if (!email) return fail(401, 'invalid_token');

      const t = now();
      const acct = await store.getAccount(email);
      const free = !acct || !acct.device_id || acct.device_id === deviceId ||
                   (t - (acct.last_seen || 0)) > cfg.IDLE_MS;
      if (free) {
        await store.putAccount({ email, device_id: deviceId, session_hash: b.sessionHash, last_seen: t });
        await store.expirePending(email);
        return { status: 200, body: { ok: true, status: 'active' } };
      }

      // Active on another phone: needs that phone's approval.
      if ((await store.countRecent(email, t - 3600 * 1000)) >= cfg.MAX_REQUESTS_PER_HOUR) {
        return fail(429, 'too_many_requests');
      }
      const id = newId();
      await store.insertRequest({
        id, email, device_id: deviceId, device_name: cleanName(b.deviceName),
        poll_hash: b.pollHash, session_hash: b.sessionHash,
        status: 'pending', created_at: t, expires_at: t + cfg.REQUEST_TTL_MS
      });
      return { status: 200, body: { ok: true, status: 'pending', requestId: id, expiresAt: t + cfg.REQUEST_TTL_MS } };
    },

    /** The waiting phone asks: was I approved? (proves itself with its poll secret) */
    async poll(b) {
      const req = await store.getRequest(String(b.requestId || ''));
      const poll = typeof b.poll === 'string' ? b.poll : '';
      if (!req || poll.length < 16 || poll.length > 128 || req.poll_hash !== await sha256Hex(poll)) {
        return fail(404, 'no_such_request');
      }
      let status = req.status;
      if (status === 'pending' && req.expires_at <= now()) {
        await store.setRequestStatus(req.id, 'expired');
        status = 'expired';
      }
      return { status: 200, body: { ok: true, status } };
    },

    /** The active phone checks in; learns about waiting sign-in requests. */
    async heartbeat(b) {
      const s = await checkSession(b.email, b.deviceId, b.session);
      if (!s) return { status: 401, body: { ok: false, reason: 'revoked' } };
      const t = now();
      if (t - (s.acct.last_seen || 0) > cfg.WRITE_THROTTLE_MS) await store.touch(s.email, t);
      const pending = await store.listPending(s.email, t);
      return {
        status: 200,
        body: {
          ok: true,
          pending: pending.map(r => ({ id: r.id, deviceName: r.device_name, createdAt: r.created_at, expiresAt: r.expires_at }))
        }
      };
    },

    /** The active phone approves or denies a waiting request. */
    async respond(b) {
      const s = await checkSession(b.email, b.deviceId, b.session);
      if (!s) return { status: 401, body: { ok: false, reason: 'revoked' } };
      const t = now();
      const req = await store.getRequest(String(b.requestId || ''));
      if (!req || req.email !== s.email || req.status !== 'pending' || req.expires_at <= t) {
        return fail(404, 'no_such_request');
      }
      if (b.approve === true) {
        await store.approve(req, t);
        return { status: 200, body: { ok: true, result: 'approved' } };
      }
      await store.setRequestStatus(req.id, 'denied');
      return { status: 200, body: { ok: true, result: 'denied' } };
    },

    /** The active phone signs out, freeing the account (next phone needs no popup). */
    async logout(b) {
      const s = await checkSession(b.email, b.deviceId, b.session);
      if (!s) return { status: 200, body: { ok: true } }; // already not active: nothing to do
      await store.clearAccount(s.email);
      await store.expirePending(s.email);
      return { status: 200, body: { ok: true } };
    }
  };
}

/* ------------------------------------------------------------------ */
/* D1 storage                                                          */
/* ------------------------------------------------------------------ */
export class D1Store {
  constructor(db) { this.db = db; }

  async getAccount(email) {
    return await this.db.prepare('SELECT email, device_id, session_hash, last_seen FROM accounts WHERE email = ?').bind(email).first();
  }
  async putAccount(a) {
    await this.db.prepare(
      'INSERT INTO accounts (email, device_id, session_hash, last_seen) VALUES (?, ?, ?, ?) ' +
      'ON CONFLICT(email) DO UPDATE SET device_id = excluded.device_id, session_hash = excluded.session_hash, last_seen = excluded.last_seen'
    ).bind(a.email, a.device_id, a.session_hash, a.last_seen).run();
  }
  async touch(email, t) {
    await this.db.prepare('UPDATE accounts SET last_seen = ? WHERE email = ?').bind(t, email).run();
  }
  async clearAccount(email) {
    await this.db.prepare('UPDATE accounts SET device_id = NULL, session_hash = NULL WHERE email = ?').bind(email).run();
  }
  async insertRequest(r) {
    await this.db.prepare(
      'INSERT INTO requests (id, email, device_id, device_name, poll_hash, session_hash, status, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)'
    ).bind(r.id, r.email, r.device_id, r.device_name, r.poll_hash, r.session_hash, r.status, r.created_at, r.expires_at).run();
  }
  async getRequest(id) {
    return await this.db.prepare('SELECT * FROM requests WHERE id = ?').bind(id).first();
  }
  async setRequestStatus(id, status) {
    await this.db.prepare('UPDATE requests SET status = ? WHERE id = ?').bind(status, id).run();
  }
  async approve(req, t) {
    await this.db.batch([
      this.db.prepare(
        'INSERT INTO accounts (email, device_id, session_hash, last_seen) VALUES (?, ?, ?, ?) ' +
        'ON CONFLICT(email) DO UPDATE SET device_id = excluded.device_id, session_hash = excluded.session_hash, last_seen = excluded.last_seen'
      ).bind(req.email, req.device_id, req.session_hash, t),
      this.db.prepare("UPDATE requests SET status = 'approved' WHERE id = ?").bind(req.id),
      this.db.prepare("UPDATE requests SET status = 'expired' WHERE email = ? AND status = 'pending' AND id <> ?").bind(req.email, req.id)
    ]);
  }
  async listPending(email, t) {
    const r = await this.db.prepare(
      "SELECT id, device_name, created_at, expires_at FROM requests WHERE email = ? AND status = 'pending' AND expires_at > ? ORDER BY created_at ASC"
    ).bind(email, t).all();
    return r.results || [];
  }
  async countRecent(email, since) {
    const r = await this.db.prepare('SELECT COUNT(*) AS c FROM requests WHERE email = ? AND created_at > ?').bind(email, since).first();
    return r ? Number(r.c) : 0;
  }
  async expirePending(email) {
    await this.db.prepare("UPDATE requests SET status = 'expired' WHERE email = ? AND status = 'pending'").bind(email).run();
  }
}

const schemaReady = new WeakMap();
export function ensureSchema(db) {
  if (!schemaReady.has(db)) {
    const p = db.batch([
      db.prepare('CREATE TABLE IF NOT EXISTS accounts (email TEXT PRIMARY KEY, device_id TEXT, session_hash TEXT, last_seen INTEGER)'),
      db.prepare('CREATE TABLE IF NOT EXISTS requests (id TEXT PRIMARY KEY, email TEXT NOT NULL, device_id TEXT NOT NULL, device_name TEXT, poll_hash TEXT NOT NULL, session_hash TEXT NOT NULL, status TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL)'),
      db.prepare('CREATE INDEX IF NOT EXISTS idx_requests_email ON requests (email, status)')
    ]).catch(e => { schemaReady.delete(db); throw e; });
    schemaReady.set(db, p);
  }
  return schemaReady.get(db);
}

/* ------------------------------------------------------------------ */
/* Google identity check                                               */
/* ------------------------------------------------------------------ */
/** Asks Google whose access token this is. Returns the verified, lower-cased email, or null. */
export async function verifyGoogleToken(token, env = {}) {
  if (!token || token.length > 4096) return null;
  try {
    const r = await fetch('https://oauth2.googleapis.com/tokeninfo?access_token=' + encodeURIComponent(token));
    if (!r.ok) return null;
    const j = await r.json();
    if (j.email_verified !== true && j.email_verified !== 'true') return null;
    if (env.ALLOWED_CLIENT_IDS) {
      const allowed = String(env.ALLOWED_CLIENT_IDS).split(',').map(s => s.trim()).filter(Boolean);
      if (allowed.length && !allowed.includes(j.aud) && !allowed.includes(j.azp)) return null;
    }
    const email = String(j.email || '').toLowerCase();
    return email || null;
  } catch (e) {
    return null;
  }
}

/* ------------------------------------------------------------------ */
/* HTTP entry point                                                    */
/* ------------------------------------------------------------------ */
const CORS = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Access-Control-Max-Age': '86400'
};
function json(body, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, 'Content-Type': 'application/json' } });
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    const url = new URL(request.url);
    if (request.method === 'GET' && url.pathname === '/') return json({ ok: true, service: 'vvault-accounts' });
    if (request.method !== 'POST' || !url.pathname.startsWith('/v1/')) return json({ ok: false, error: 'not_found' }, 404);

    const route = url.pathname.slice(4);
    if (!['login', 'poll', 'heartbeat', 'respond', 'logout'].includes(route)) return json({ ok: false, error: 'not_found' }, 404);

    const text = await request.text();
    if (text.length > 8192) return json({ ok: false, error: 'too_large' }, 413);
    let body;
    try { body = JSON.parse(text || '{}'); } catch (e) { return json({ ok: false, error: 'bad_json' }, 400); }
    if (!body || typeof body !== 'object') return json({ ok: false, error: 'bad_json' }, 400);

    try {
      await ensureSchema(env.DB);
      const api = createApi({ store: new D1Store(env.DB), verifyToken: t => verifyGoogleToken(t, env) });
      const r = await api[route](body);
      return json(r.body, r.status);
    } catch (e) {
      return json({ ok: false, error: 'server_error' }, 500);
    }
  }
};
