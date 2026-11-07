import express from 'express';
import cookieParser from 'cookie-parser';
import { fileURLToPath } from 'node:url';
import { accessToken, appOrigin, callback, login, logout } from './auth.mjs';

const app = express();
const users = new URL(process.env.USER_SERVICE_BASE_URL);
const messages = new URL(process.env.MESSAGING_SERVICE_BASE_URL);
const browser = fileURLToPath(new URL('../dist/messaging-frontend/browser/', import.meta.url));
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;

app.disable('x-powered-by');
app.use((req, res, next) => {
  res.set({
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'no-referrer',
    'Content-Security-Policy': "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
  });
  next();
});
app.use(cookieParser());
app.use('/api', (req, res, next) => {
  res.set('Cache-Control', 'no-store');
  if (req.method !== 'GET' && req.method !== 'HEAD' && req.get('Origin') !== appOrigin) {
    return res.status(403).json({ error: 'Origin rejected' });
  }
  next();
});
app.use('/api', express.json({ limit: '32kb' }));
app.get('/health', (req, res) => res.sendStatus(200));
app.get('/api/auth/login', login);
app.get('/callback', (req, res, next) => {
  res.set('Cache-Control', 'no-store');
  return callback(req, res, next);
});
app.post('/api/auth/logout', logout);

app.post('/api/register', (req, res) => {
  const key = req.get('Idempotency-Key');
  if (!uuid.test(key ?? '')) return res.status(400).json({ error: 'A valid signup key is required' });
  return forward(req, res, users, '/api/users/register', null, { 'Idempotency-Key': key });
});

app.get('/api/session', authenticated(users, () => '/api/users/me'));
app.get('/api/conversations', authenticated(messages, () => '/api/conversations?limit=100'));
app.post('/api/conversations', authenticated(messages, () => '/api/conversations'));
for (const method of ['get', 'post']) {
  app[method]('/api/conversations/:id/messages', authenticated(messages, req => {
    if (!uuid.test(req.params.id)) return null;
    const query = new URLSearchParams();
    for (const name of ['before', 'limit']) {
      if (typeof req.query[name] === 'string') query.set(name, req.query[name]);
    }
    return '/api/conversations/' + req.params.id + '/messages?' + query;
  }));
}
app.use('/api', (req, res) => res.sendStatus(404));

function authenticated(service, pathFor) {
  return async (req, res) => {
    const path = pathFor(req);
    if (path === null) return res.sendStatus(400);
    const token = await accessToken(req, res);
    if (!token) return res.sendStatus(401);
    return forward(req, res, service, path, token);
  };
}

async function forward(req, res, service, path, token, extraHeaders = {}) {
  const headers = new Headers({ ...extraHeaders, Accept: 'application/json' });
  const hasBody = req.method === 'POST';
  if (hasBody) headers.set('Content-Type', 'application/json');
  if (token) headers.set('Authorization', 'Bearer ' + token);

  const result = await fetch(new URL(path, service), {
    method: req.method,
    headers,
    body: hasBody ? JSON.stringify(req.body ?? {}) : undefined,
    redirect: 'error',
    signal: AbortSignal.timeout(path === '/api/users/register' ? 45_000 : 12_000),
  });
  const retryAfter = result.headers.get('Retry-After');
  if (retryAfter) res.set('Retry-After', retryAfter);
  if (!result.ok) return res.status(result.status).json({ error: result.statusText });
  if (result.status === 202 || result.status === 204) return res.status(result.status).end();
  res.status(result.status).json(await result.json());
}

app.use(express.static(browser, { index: false, redirect: false, maxAge: '1d' }));
app.get('/', (req, res) => res.set('Cache-Control', 'no-cache').sendFile(browser + 'index.html'));
app.use((error, req, res, next) => {
  if (res.headersSent) return next(error);
  if (error.status === 400 || error.status === 413) {
    return res.status(error.status).json({ error: 'Invalid request body' });
  }
  console.warn('Frontend request failed:', error.code ?? error.name);
  res.status(503).json({ error: 'Service temporarily unavailable' });
});

app.listen(Number(process.env.PORT ?? 3000), '0.0.0.0', () => console.log('Relay frontend listening'));
