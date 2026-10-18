import { test } from "node:test";
import * as assert from "node:assert";
import * as crypto from "node:crypto";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import * as zlib from "node:zlib";

import { ServerConfig, DirRule } from "../src/config.js";
import { SyncCore } from "../src/core.js";
import { buildApp } from "../src/server.js";
import type { FastifyInstance } from "fastify";

const TOKEN = "test-secret-token";

const DIRS_FLAT: DirRule[] = [
  { path: "mods", tier: "authoritative", owner: "both", mode: "mirror", exclude: ["**/*.disabled"] },
  { path: "config", tier: "authoritative", owner: "both", mode: "mirror", exclude: [] },
  { path: "resourcepacks", tier: "authoritative", owner: "both", mode: "mirror", exclude: [] },
  { path: "journeymap", tier: "union", owner: "client", mode: "union", exclude: [] },
  { path: "options.txt", tier: "union", owner: "client", mode: "line-union", linePrefix: "key_" },
];

const DIRS_PROFILE: DirRule[] = [
  { path: "mods", tier: "authoritative", owner: "both", mode: "mirror", exclude: ["**/*.disabled"] },
  { path: "config", tier: "authoritative", owner: "both", mode: "mirror", exclude: [] },
  { path: "resourcepacks", tier: "authoritative", owner: "both", mode: "mirror", exclude: [] },
  { path: "scripts", tier: "authoritative", owner: "both", mode: "mirror", exclude: [] },
  { path: "journeymap", tier: "union", owner: "client", mode: "union", exclude: [], perAccount: true },
  { path: "schematics", tier: "union", owner: "client", mode: "union", exclude: [], perAccount: true },
  { path: "options.txt", tier: "union", owner: "client", mode: "line-union", linePrefix: "key_", perAccount: true },
];

const EXPECTED_FLAT: Record<string, string> = {
  "mods/AAA-1.0.jar": "aaa-bytes",
  "mods/BBB-2.0.jar": "bbb-bytes-longer",
  "config/some.cfg": "config-content",
  "resourcepacks/MyPack.zip": "pack-bytes",
};
const EXPECTED_PROFILE: Record<string, string> = {
  ...EXPECTED_FLAT,
  "scripts/zzz.zs": "print('hi')",
};

const OPTIONS_LOCAL =
  "version:1343\n" +
  "renderDistance:12\n" +
  "fov:0.0\n" +
  "key_key.attack:key.mouse.left\n" +
  "key_key.jump:key.keyboard.space\n";

// ---------------------------------------------------------------------------
// 测试用 ustar tar.gz 生成器（与 Java 端 TarGzWriter 同格式：typeflag '0'、长名走 prefix+name）
// ---------------------------------------------------------------------------
function writeOctal(header: Buffer, offset: number, length: number, value: number): void {
  const s = value.toString(8).padStart(length - 1, "0");
  header.write(s, offset, "ascii");
  header[offset + length - 1] = 0;
}

function ustarHeader(name: string, size: number): Buffer {
  const header = Buffer.alloc(512);
  const nameBytes = Buffer.from(name, "utf-8");
  if (nameBytes.length <= 100) {
    nameBytes.copy(header, 0);
  } else {
    const idx = name.lastIndexOf("/");
    const prefix = name.slice(0, idx);
    const base = name.slice(idx + 1);
    Buffer.from(base, "utf-8").copy(header, 0);
    Buffer.from(prefix, "utf-8").copy(header, 345);
  }
  writeOctal(header, 100, 8, 0o644);
  writeOctal(header, 108, 8, 0);
  writeOctal(header, 116, 8, 0);
  writeOctal(header, 124, 12, size);
  writeOctal(header, 136, 12, 0);
  header[156] = 0x30; // '0' 普通文件
  header.write("ustar", 257, "ascii");
  header[262] = 0;
  header.write("00", 263, "ascii");
  for (let i = 148; i < 156; i++) header[i] = 0x20;
  let sum = 0;
  for (const b of header) sum += b;
  header.write(sum.toString(8).padStart(6, "0") + "\0 ", 148, "ascii");
  return header;
}

function makeTar(entries: Record<string, Buffer | string>): Buffer {
  const blocks: Buffer[] = [];
  for (const [name, data] of Object.entries(entries)) {
    const buf = typeof data === "string" ? Buffer.from(data) : data;
    blocks.push(ustarHeader(name, buf.length));
    blocks.push(buf);
    const pad = (512 - (buf.length % 512)) % 512;
    if (pad) blocks.push(Buffer.alloc(pad));
  }
  blocks.push(Buffer.alloc(512));
  blocks.push(Buffer.alloc(512));
  return zlib.gzipSync(Buffer.concat(blocks));
}

interface Ctx {
  tmp: string;
  repo: string;
  app: FastifyInstance;
  write(rel: string, data: Buffer | string): void;
  read(rel: string): Buffer;
  sha256(data: Buffer | string): string;
}

function makeCtx(dirs: DirRule[], expected: Record<string, string>): Ctx {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "mc-sync-test-"));
  const repo = path.join(tmp, "repo");
  for (const sub of ["mods", "config", "resourcepacks", "scripts", "journeymap"]) {
    fs.mkdirSync(path.join(repo, sub), { recursive: true });
  }
  const cfg: ServerConfig = {
    repo_root: repo,
    state_file: path.join(tmp, "state.json"),
    snapshot_dir: path.join(tmp, "snapshots"),
    snapshot_keep: 20,
    submit_token: TOKEN,
    dirs,
  };
  const core = new SyncCore(cfg);
  const app = buildApp(core);

  const ctx: Ctx = {
    tmp,
    repo,
    app,
    write(rel, data) {
      const p = path.join(repo, ...rel.split("/"));
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, data);
    },
    read(rel) {
      return fs.readFileSync(path.join(repo, ...rel.split("/")));
    },
    sha256(data) {
      const buf = typeof data === "string" ? Buffer.from(data) : data;
      return crypto.createHash("sha256").update(buf).digest("hex");
    },
  };
  for (const [rel, data] of Object.entries(expected)) ctx.write(rel, data);
  return ctx;
}

async function getJson(app: FastifyInstance, url: string): Promise<[number, any]> {
  const res = await app.inject({ method: "GET", url });
  return [res.statusCode, res.json()];
}

async function postJson(
  app: FastifyInstance,
  url: string,
  body: Buffer,
  headers: Record<string, string> = {}
): Promise<[number, any]> {
  const res = await app.inject({
    method: "POST",
    url,
    payload: body,
    headers: { "content-type": "application/gzip", ...headers },
  });
  return [res.statusCode, res.json()];
}

function submit(app: FastifyInstance, entries: Record<string, Buffer | string>, profile?: string): Promise<[number, any]> {
  const url = profile ? `/submit?profile=${profile}` : "/submit";
  return postJson(app, url, makeTar(entries), { "x-sync-token": TOKEN });
}

// ---------------------------------------------------------------------------
// A/B：老行为（并集档在仓库根）
// ---------------------------------------------------------------------------
test("health", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  const [status, obj] = await getJson(ctx.app, "/health");
  assert.equal(status, 200);
  assert.equal(obj.ok, true);
  assert.equal(obj.version, 1);
});

test("manifest protocol2 and initial", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  const [status, manifest] = await getJson(ctx.app, "/manifest");
  assert.equal(status, 200);
  assert.equal(manifest.protocol, 2);
  assert.equal(manifest.version, 1);
  assert.equal(manifest.profile, "default");
  assert.ok(manifest.generated);

  const dirPaths = new Set(manifest.dirs.map((d: any) => d.path));
  for (const expected of ["mods", "config", "resourcepacks", "journeymap"]) {
    assert.ok(dirPaths.has(expected));
  }
  assert.ok(dirPaths.has("options.txt"));

  const files = new Map(manifest.files.map((f: any) => [f.path, f]));
  assert.deepEqual(new Set(files.keys()), new Set(Object.keys(EXPECTED_FLAT)));
  for (const [rel, data] of Object.entries(EXPECTED_FLAT)) {
    const entry = files.get(rel)!;
    assert.equal(entry.size, Buffer.byteLength(data));
    assert.equal(entry.sha256, ctx.sha256(data));
    assert.equal(entry.dir, rel.split("/")[0]);
    assert.equal(entry.localPath, rel);
  }
});

test("fetch file matches", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  const res = await ctx.app.inject({ method: "GET", url: "/f/mods/AAA-1.0.jar" });
  assert.equal(res.statusCode, 200);
  assert.equal(res.headers["content-type"], "application/octet-stream");
  assert.deepEqual(res.rawPayload, ctx.read("mods/AAA-1.0.jar"));
});

test("fetch missing 404", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  const res = await ctx.app.inject({ method: "GET", url: "/f/nope" });
  assert.equal(res.statusCode, 404);
});

test("fetch traversal not 200", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  const res = await ctx.app.inject({ method: "GET", url: "/f/../../etc/passwd" });
  assert.ok(res.statusCode === 403 || res.statusCode === 404);
});

test("unlisted file not served", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  ctx.write("secret.txt", "top-secret");
  const res1 = await ctx.app.inject({ method: "GET", url: "/f/secret.txt" });
  assert.equal(res1.statusCode, 404);
  const res2 = await ctx.app.inject({ method: "GET", url: "/f/mods/AAA-1.0.jar" });
  assert.equal(res2.statusCode, 200);
});

test("submit no token 403", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  const [status, obj] = await postJson(ctx.app, "/submit", makeTar({ "journeymap/x.txt": "hi" }));
  assert.equal(status, 403);
  assert.ok(obj.error);
});

test("submit wrong token 403", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  const [status] = await postJson(ctx.app, "/submit", makeTar({ "journeymap/x.txt": "hi" }), {
    "x-sync-token": "wrong-token",
  });
  assert.equal(status, 403);
});

test("submit union dir lands in repo", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  const payload = "hello-world";
  const [status, obj] = await submit(ctx.app, { "journeymap/x.txt": payload });
  assert.equal(status, 200);
  assert.equal(obj.ok, true);
  assert.equal(obj.version, 2);
  assert.ok(obj.accepted.includes("journeymap/x.txt"));
  assert.deepEqual(obj.rejected, []);
  assert.equal(ctx.read("journeymap/x.txt").toString(), payload);
  assert.ok(fs.readdirSync(path.join(ctx.tmp, "snapshots")).some((n) => n.startsWith("v2-") && n.endsWith(".tar.gz")));
});

test("manifest after submit", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  const [s] = await submit(ctx.app, { "journeymap/y.txt": "second" });
  assert.equal(s, 200);
  const [status, manifest] = await getJson(ctx.app, "/manifest");
  assert.equal(manifest.version, 2);
  assert.ok(manifest.files.some((f: any) => f.path === "journeymap/y.txt"));
});

test("submit rejects non-union", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  const [status, obj] = await submit(ctx.app, { "journeymap/z.txt": "legit", "mods/hack.jar": "evil" });
  assert.equal(status, 200);
  const rejected = new Map(obj.rejected.map((r: any) => [r.path, r.reason]));
  assert.ok(rejected.has("mods/hack.jar"));
  assert.equal(ctx.read("journeymap/z.txt").toString(), "legit");
  assert.ok(!fs.existsSync(path.join(ctx.repo, "mods", "hack.jar")));
});

test("submit key lines merge", async () => {
  const ctx = makeCtx(DIRS_FLAT, EXPECTED_FLAT);
  const body = "key_key.attack:key.keyboard.f\nnot a key line\n";
  const [status, obj] = await submit(ctx.app, { "options.txt": body });
  assert.equal(status, 200);
  assert.deepEqual(obj.accepted, ["options.txt"]);
  assert.equal(obj.keyLines, 1);
  assert.equal(ctx.read("options.txt").toString(), "key_key.attack:key.keyboard.f\n");
});

// ---------------------------------------------------------------------------
// C~G：账号档案
// ---------------------------------------------------------------------------
test("manifest default profile has no per-account files", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  const [status, manifest] = await getJson(ctx.app, "/manifest");
  assert.equal(status, 200);
  assert.equal(manifest.profile, "default");
  assert.deepEqual(
    new Set(manifest.files.map((f: any) => f.path)),
    new Set(Object.keys(EXPECTED_PROFILE))
  );
  const byPath = new Map(manifest.dirs.map((d: any) => [d.path, d]));
  assert.equal(byPath.get("journeymap").repoPath, "profiles/default/journeymap");
  assert.equal(byPath.get("journeymap").perAccount, true);
  assert.equal(byPath.get("schematics").repoPath, "profiles/default/schematics");
  assert.equal(byPath.get("options.txt").repoPath, "profiles/default/options.key.txt");
  assert.equal(byPath.get("mods").repoPath, "mods");
  assert.ok(!byPath.has("visualprospecting"));
});

test("submit maps into profile tree", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  const [status, obj] = await submit(ctx.app, { "journeymap/data/mp/w/DIM0/1,2.png": "png" }, "Fgod");
  assert.equal(status, 200);
  assert.equal(obj.profile, "Fgod");
  assert.ok(obj.accepted.includes("journeymap/data/mp/w/DIM0/1,2.png"));
  assert.deepEqual(obj.rejected, []);
  assert.equal(ctx.read("profiles/Fgod/journeymap/data/mp/w/DIM0/1,2.png").toString(), "png");
  assert.ok(!fs.existsSync(path.join(ctx.repo, "journeymap", "data")));
});

test("manifest lists profile files with local path", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  await submit(ctx.app, { "journeymap/a.png": "a" }, "Fgod");
  await submit(ctx.app, { "schematics/home.schematic": "s" }, "Fgod");
  const [status, manifest] = await getJson(ctx.app, "/manifest?profile=Fgod");
  assert.equal(status, 200);
  assert.equal(manifest.profile, "Fgod");
  const files = new Map(manifest.files.map((f: any) => [f.path, f]));
  const jm = files.get("profiles/Fgod/journeymap/a.png")!;
  assert.equal(jm.dir, "journeymap");
  assert.equal(jm.localPath, "journeymap/a.png");
  assert.equal(jm.tier, "union");
  assert.equal(jm.owner, "client");
  const sc = files.get("profiles/Fgod/schematics/home.schematic")!;
  assert.equal(sc.localPath, "schematics/home.schematic");
  assert.ok(files.has("mods/AAA-1.0.jar"));
});

test("fetch profile file", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  await submit(ctx.app, { "journeymap/a.png": "abc" }, "Fgod");
  await getJson(ctx.app, "/manifest?profile=Fgod");
  const res = await ctx.app.inject({ method: "GET", url: "/f/profiles/Fgod/journeymap/a.png" });
  assert.equal(res.statusCode, 200);
  assert.equal(res.rawPayload.toString(), "abc");
});

test("options key lines merge into key file", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  const [s1, o1] = await submit(ctx.app, { "options.txt": OPTIONS_LOCAL }, "Fgod");
  assert.equal(s1, 200);
  assert.equal(o1.keyLines, 2);
  assert.equal(
    ctx.read("profiles/Fgod/options.key.txt").toString(),
    "key_key.attack:key.mouse.left\nkey_key.jump:key.keyboard.space\n"
  );
  const [s2, o2] = await submit(ctx.app, { "options.txt": "key_key.jump:key.keyboard.keypad.0\n" }, "Fgod");
  assert.equal(s2, 200);
  assert.equal(o2.keyLines, 2);
  assert.equal(
    ctx.read("profiles/Fgod/options.key.txt").toString(),
    "key_key.attack:key.mouse.left\nkey_key.jump:key.keyboard.keypad.0\n"
  );
});

test("options key file listed with line prefix", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  await submit(ctx.app, { "options.txt": OPTIONS_LOCAL }, "Fgod");
  const [status, manifest] = await getJson(ctx.app, "/manifest?profile=Fgod");
  const entry = new Map(manifest.files.map((f: any) => [f.path, f])).get("profiles/Fgod/options.key.txt")!;
  assert.equal(entry.dir, "options.txt");
  assert.equal(entry.localPath, "options.txt");
  assert.equal(entry.linePrefix, "key_");
  const res = await ctx.app.inject({ method: "GET", url: "/f/profiles/Fgod/options.key.txt" });
  assert.equal(res.statusCode, 200);
  assert.equal(res.rawPayload.toString(), "key_key.attack:key.mouse.left\nkey_key.jump:key.keyboard.space\n");
});

test("profiles are isolated", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  await submit(ctx.app, { "journeymap/a.png": "fgod" }, "Fgod");
  await submit(ctx.app, { "journeymap/a.png": "alt" }, "Alt");
  assert.equal(ctx.read("profiles/Fgod/journeymap/a.png").toString(), "fgod");
  assert.equal(ctx.read("profiles/Alt/journeymap/a.png").toString(), "alt");
  const [status, manifest] = await getJson(ctx.app, "/manifest?profile=Alt");
  const paths = new Set(manifest.files.map((f: any) => f.path));
  assert.ok(paths.has("profiles/Alt/journeymap/a.png"));
  assert.ok(!paths.has("profiles/Fgod/journeymap/a.png"));
});

test("invalid profile rejected", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  for (const bad of ["../evil", "a/b", "has space", "x".repeat(33), "中文"]) {
    const [status] = await getJson(ctx.app, "/manifest?profile=" + encodeURIComponent(bad));
    assert.equal(status, 400, `profile=${bad} 应 400`);
  }
  const res = await ctx.app.inject({
    method: "POST",
    url: "/submit?profile=" + encodeURIComponent("../evil"),
    payload: makeTar({ "journeymap/x": "1" }),
    headers: { "content-type": "application/gzip", "x-sync-token": TOKEN },
  });
  assert.equal(res.statusCode, 400);
});

test("submit traversal member rejected", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  const [status, obj] = await submit(ctx.app, { "journeymap/../../escape.txt": "x" }, "Fgod");
  assert.equal(status, 200);
  const reasons = new Map(obj.rejected.map((r: any) => [r.path, r.reason]));
  assert.ok(reasons.has("journeymap/../../escape.txt"));
  assert.ok(!fs.existsSync(path.join(ctx.tmp, "escape.txt")));
});

test("submit authoritative member still rejected", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  const [status, obj] = await submit(ctx.app, { "mods/hack.jar": "evil" }, "Fgod");
  assert.equal(status, 200);
  assert.deepEqual(
    new Map(obj.rejected.map((r: any) => [r.path, r.reason])),
    new Map([["mods/hack.jar", "not-union-tier"]])
  );
});

test("manifest unknown profile lists union dirs but no creation", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  const [status, manifest] = await getJson(ctx.app, "/manifest?profile=Brand");
  assert.equal(status, 200);
  assert.equal(manifest.profile, "Brand");
  assert.deepEqual(
    manifest.files.filter((f: any) => f.path.startsWith("profiles/")),
    []
  );
  assert.ok(!fs.existsSync(path.join(ctx.repo, "profiles", "Brand")));
});

test("empty options submit is not silently accepted", async () => {
  const ctx = makeCtx(DIRS_PROFILE, EXPECTED_PROFILE);
  const [status, obj] = await submit(ctx.app, { "options.txt": "version:1343\nfov:0.0\n" }, "Fgod");
  assert.equal(status, 200);
  assert.equal(obj.keyLines, 0);
  assert.deepEqual(
    new Map(obj.rejected.map((r: any) => [r.path, r.reason])),
    new Map([["options.txt", "no-key-lines"]])
  );
  assert.ok(!fs.existsSync(path.join(ctx.repo, "profiles", "Fgod", "options.key.txt")));
});
