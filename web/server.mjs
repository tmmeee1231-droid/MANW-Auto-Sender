import express from "express";
import helmet from "helmet";
import multer from "multer";
import { WebSocketServer } from "ws";
import {
  randomBytes, scrypt as scryptCallback, timingSafeEqual, createHash
} from "node:crypto";
import { promisify } from "node:util";
import fs from "node:fs/promises";
import path from "node:path";
import http from "node:http";

const scrypt = promisify(scryptCallback);
const app = express();
const server = http.createServer(app);
const wss = new WebSocketServer({ noServer: true });
const PORT = Number(process.env.PORT || 8080);
const DATA_DIR = process.env.DATA_DIR || "/data";
const STATE_FILE = path.join(DATA_DIR, "state.json");
const MEDIA_DIR = path.join(DATA_DIR, "media");
const sessions = new Map();
const viewers = new Set();
const deviceSockets = new Set();
const commands = [];
let state;

await fs.mkdir(MEDIA_DIR, { recursive: true });
async function hashPassword(password, salt = randomBytes(16).toString("hex")) {
  const key = await scrypt(password, salt, 64);
  return { salt, hash: Buffer.from(key).toString("hex") };
}
let saveQueue = Promise.resolve();
async function saveState() {
  const snapshot = JSON.stringify(state, null, 2);
  const write = async () => {
    const temp = STATE_FILE + "." + randomBytes(12).toString("hex") + ".tmp";
    try {
      await fs.writeFile(temp, snapshot, { mode: 0o600 });
      await fs.rename(temp, STATE_FILE);
    } catch (error) {
      await fs.rm(temp, { force: true }).catch(() => {});
      throw error;
    }
  };
  saveQueue = saveQueue.then(write, write);
  return saveQueue;
}
try {
  state = JSON.parse(await fs.readFile(STATE_FILE, "utf8"));
} catch {
  const initial = process.env.INITIAL_ADMIN_PASSWORD;
  if (!initial || initial.length < 10) {
    console.error("Set INITIAL_ADMIN_PASSWORD (at least 10 characters) before starting.");
    process.exit(1);
  }
  state = {
    password: await hashPassword(initial),
    deviceTokenHash: null,
    paired: false,
    lastSeen: null,
    cameraActive: false,
    battery: null,
    media: [],
    createdAt: new Date().toISOString()
  };
  await saveState();
}
if (!state.media) state.media = [];

app.disable("x-powered-by");
app.use(helmet({ contentSecurityPolicy: false, crossOriginEmbedderPolicy: false }));
app.use(express.json({ limit: "1mb" }));
app.use(express.static(path.join(process.cwd(), "web", "public"), { index: "index.html" }));

function parseCookies(header = "") {
  return Object.fromEntries(header.split(";").map(v => v.trim()).filter(Boolean).map(v => {
    const i = v.indexOf("="); return [v.slice(0, i), decodeURIComponent(v.slice(i + 1))];
  }));
}
function getSession(req) {
  const token = parseCookies(req.headers.cookie).manw_session;
  const session = token && sessions.get(token);
  if (!session || session.expires < Date.now()) return null;
  return session;
}
function requireAuth(req, res, next) {
  if (!getSession(req)) return res.status(401).json({ error: "سجّل الدخول أولاً" });
  next();
}
function requireDevice(req, res, next) {
  const token = req.headers["x-manw-device"] || req.query.device;
  if (!token || !state.deviceTokenHash) return res.status(401).json({ error: "الجهاز غير مقترن" });
  const actual = createHash("sha256").update(String(token)).digest();
  const expected = Buffer.from(state.deviceTokenHash, "hex");
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) return res.status(401).json({ error: "رمز الجهاز غير صحيح" });
  next();
}
function cookie(res, value, maxAge) {
  res.setHeader("Set-Cookie", `manw_session=${encodeURIComponent(value)}; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=${maxAge}`);
}
function broadcast(payload) {
  const data = JSON.stringify(payload);
  for (const ws of viewers) if (ws.readyState === 1) ws.send(data);
}

app.post("/api/login", async (req, res) => {
  const password = String(req.body?.password || "");
  if (password.length > 256) return res.status(400).json({ error: "بيانات غير صالحة" });
  const candidate = await scrypt(password, state.password.salt, 64);
  const expected = Buffer.from(state.password.hash, "hex");
  if (expected.length !== candidate.length || !timingSafeEqual(expected, Buffer.from(candidate))) {
    await new Promise(r => setTimeout(r, 500));
    return res.status(401).json({ error: "كلمة المرور غير صحيحة" });
  }
  const token = randomBytes(32).toString("base64url");
  sessions.set(token, { expires: Date.now() + 12 * 60 * 60 * 1000 });
  cookie(res, token, 12 * 60 * 60);
  res.json({ ok: true });
});
app.post("/api/logout", requireAuth, (req, res) => {
  const token = parseCookies(req.headers.cookie).manw_session;
  sessions.delete(token); cookie(res, "", 0); res.json({ ok: true });
});
app.get("/api/session", (req, res) => res.json({ authenticated: !!getSession(req) }));
app.post("/api/password", requireAuth, async (req, res) => {
  const current = String(req.body?.currentPassword || "");
  const next = String(req.body?.newPassword || "");
  const candidate = await scrypt(current, state.password.salt, 64);
  if (!timingSafeEqual(Buffer.from(candidate), Buffer.from(state.password.hash, "hex"))) return res.status(403).json({ error: "كلمة المرور الحالية غير صحيحة" });
  if (next.length < 10 || next.length > 128) return res.status(400).json({ error: "استخدم كلمة مرور من 10 إلى 128 حرفًا" });
  state.password = await hashPassword(next); await saveState();
  sessions.clear(); cookie(res, "", 0);
  res.json({ ok: true, message: "تم تغيير كلمة المرور. سجّل الدخول من جديد." });
});
app.get("/api/status", requireAuth, (req, res) => res.json({
  paired: !!state.paired,
  lastSeen: state.lastSeen,
  cameraActive: !!state.cameraActive,
  battery: state.battery,
  mediaCount: state.media.length,
  serverTime: new Date().toISOString()
}));
app.get("/api/media", requireAuth, (req, res) => res.json({ items: state.media.slice(0, 100) }));
app.get("/api/media/:id", requireAuth, async (req, res) => {
  const item = state.media.find(x => x.id === req.params.id);
  if (!item) return res.sendStatus(404);
  res.sendFile(path.join(MEDIA_DIR, item.file));
});
const storage = multer.diskStorage({
  destination: (_req, _file, cb) => cb(null, MEDIA_DIR),
  filename: (_req, file, cb) => cb(null, randomBytes(16).toString("hex") + path.extname(file.originalname).slice(0, 8))
});
const upload = multer({ storage, limits: { fileSize: 40 * 1024 * 1024, files: 1 } });
app.post("/api/device/pair-code", requireAuth, (_req, res) => {
  const code = randomBytes(4).toString("hex").toUpperCase();
  pairCodes.set(code, Date.now() + 5 * 60 * 1000);
  res.json({ code, expiresInSeconds: 300 });
});
const pairCodes = new Map();
app.post("/api/device/pair", async (req, res) => {
  const code = String(req.body?.code || "").toUpperCase();
  const expires = pairCodes.get(code);
  if (!expires || expires < Date.now()) return res.status(403).json({ error: "رمز الاقتران غير صالح أو انتهت صلاحيته" });
  pairCodes.delete(code);
  const token = randomBytes(32).toString("base64url");
  state.deviceTokenHash = createHash("sha256").update(token).digest("hex");
  state.paired = true; await saveState();
  res.json({ deviceToken: token });
});
app.post("/api/device/heartbeat", requireDevice, async (req, res) => {
  state.lastSeen = new Date().toISOString();
  if (Number.isFinite(req.body?.battery)) state.battery = Math.max(0, Math.min(100, req.body.battery));
  if (typeof req.body?.cameraActive === "boolean") state.cameraActive = req.body.cameraActive;
  await saveState(); broadcast({ type: "status" }); res.json({ ok: true });
});
app.get("/api/device/commands", requireDevice, (_req, res) => {
  res.json({ commands: commands.splice(0, 10) });
});
app.post("/api/device/media", requireDevice, upload.single("file"), async (req, res) => {
  if (!req.file) return res.status(400).json({ error: "الملف مفقود" });
  const item = { id: randomBytes(12).toString("hex"), file: req.file.filename, name: path.basename(req.file.originalname), type: req.file.mimetype, size: req.file.size, createdAt: new Date().toISOString() };
  state.media.unshift(item); state.media = state.media.slice(0, 500);
  await saveState(); broadcast({ type: "media", item }); res.json({ ok: true, item });
});
app.post("/api/control", requireAuth, (req, res) => {
  const allowed = new Set(["camera_start", "camera_stop", "capture", "focus_center", "zoom", "record_start", "record_stop", "whatsapp_open", "screen_start", "screen_stop"]);
  const command = String(req.body?.command || "");
  if (!allowed.has(command)) return res.status(400).json({ error: "أمر غير مدعوم" });
  if (!state.paired) return res.status(409).json({ error: "اربط الهاتف أولاً من التطبيق" });
  const payload = {};
  if (command === "zoom") {\n    const value = Number(req.body?.value);\n    if (!Number.isFinite(value)) return res.status(400).json({ error: "قيمة التقريب غير صالحة" });\n    payload.value = Math.max(0, Math.min(1, value));\n  }\n  if (command === "whatsapp_open") {
    const phone = String(req.body?.phone || "").replace(/[^0-9]/g, "");
    const message = String(req.body?.message || "").slice(0, 1000);
    if (phone.length < 8 || phone.length > 15) return res.status(400).json({ error: "اكتب رقم واتساب دوليًا صحيحًا من 8 إلى 15 رقمًا" });
    payload.phone = phone;
    payload.message = message;
  }
  const queuedCommand = command === "zoom" ? "zoom:" + payload.value : command;\n  commands.push({ id: randomBytes(8).toString("hex"), command: queuedCommand, ...payload, createdAt: new Date().toISOString() });
  broadcast({ type: "command", command });
  res.json({ ok: true, queued: true });
});
app.get("/api/events", requireAuth, (req, res) => {
  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("Connection", "keep-alive");
  res.write("event: ready\ndata: {}\n\n");
  const timer = setInterval(() => res.write(": keepalive\n\n"), 20000);
  req.on("close", () => clearInterval(timer));
});

server.on("upgrade", (req, socket, head) => {
  const url = new URL(req.url, "http://localhost");
  const cookies = parseCookies(req.headers.cookie);
  const session = sessions.get(cookies.manw_session);
  if (url.pathname === "/ws/view" && session && session.expires > Date.now()) {
    wss.handleUpgrade(req, socket, head, ws => {
      viewers.add(ws);
      ws.on("message", raw => {
        try {
          const msg = JSON.parse(raw.toString());
          if (msg.type !== "remote_input") return;
          const p = msg.payload || {};
          const action = String(p.action || "");
          if (!["tap", "swipe", "text", "back", "home"].includes(action)) return;
          if (action === "text" && (typeof p.value !== "string" || p.value.length > 1000)) return;
          const clean = { action };
          for (const key of ["x","y","x1","y1","x2","y2","duration"]) {
            if (Number.isFinite(p[key])) clean[key] = Math.max(0, Math.min(key === "duration" ? 1200 : 1, p[key]));
          }
          if (action === "text") clean.value = p.value;
          const payload = JSON.stringify({ type: "remote_input", payload: clean });
          for (const device of deviceSockets) if (device.readyState === 1) device.send(payload);
        } catch {}
      });
      ws.on("close", () => viewers.delete(ws));
    });
    return;
  }
  if (url.pathname === "/ws/device") {
    const token = url.searchParams.get("device") || "";
    const actual = createHash("sha256").update(token).digest();
    const expected = state.deviceTokenHash ? Buffer.from(state.deviceTokenHash, "hex") : Buffer.alloc(0);
    if (expected.length && actual.length === expected.length && timingSafeEqual(actual, expected)) {
      wss.handleUpgrade(req, socket, head, ws => {
        deviceSockets.add(ws);
        ws.on("message", data => {
          try {
            const msg = JSON.parse(data.toString());
            if ((msg.type === "frame" || msg.type === "screen_frame") && typeof msg.jpeg === "string" && msg.jpeg.length < 2_000_000) broadcast({ type: "frame", source: msg.type, jpeg: msg.jpeg });
            if (msg.type === "status") { state.lastSeen = new Date().toISOString(); state.cameraActive = !!msg.cameraActive; if (Number.isFinite(msg.battery)) state.battery = msg.battery; }
          } catch {}
        });
        ws.on("close", () => deviceSockets.delete(ws));
      });
      return;
    }
  }
  socket.write("HTTP/1.1 401 Unauthorized\r\n\r\n"); socket.destroy();
});

server.listen(PORT, "0.0.0.0", () => console.log(`MANW Control listening on ${PORT}`));
