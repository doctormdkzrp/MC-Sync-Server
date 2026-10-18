import { test } from "node:test";
import * as assert from "node:assert";
import { spawn, ChildProcess } from "node:child_process";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";

import { ServerConfig, DirRule } from "../src/config.js";

const SERVER_DIR = path.resolve(import.meta.dirname, "..");

const DIRS: DirRule[] = [
  { path: "mods", tier: "authoritative", owner: "both", mode: "mirror", exclude: ["**/*.disabled"] },
  { path: "config", tier: "authoritative", owner: "both", mode: "mirror", exclude: [] },
  { path: "journeymap", tier: "union", owner: "client", mode: "union", exclude: [], perAccount: true },
];

/** 起真进程、打真接口——专治「能编译、单测绿、但服务起不来」这类入口层 bug。 */
test("CLI 起服务后能对外服务（/health + /manifest）", async () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "mc-sync-cli-"));
  const repo = path.join(tmp, "repo");
  fs.mkdirSync(path.join(repo, "mods"), { recursive: true });
  fs.mkdirSync(path.join(repo, "config"), { recursive: true });
  fs.mkdirSync(path.join(repo, "journeymap"), { recursive: true });
  fs.writeFileSync(path.join(repo, "mods", "AAA-1.0.jar"), "aaa-bytes");

  const cfg: ServerConfig = {
    repo_root: repo,
    state_file: path.join(tmp, "state.json"),
    snapshot_dir: path.join(tmp, "snapshots"),
    snapshot_keep: 5,
    listen: { host: "127.0.0.1", port: 0 }, // 让系统挑端口
    submit_token: "cli-token",
    dirs: DIRS,
  };
  const cfgPath = path.join(tmp, "config.json");
  fs.writeFileSync(cfgPath, JSON.stringify(cfg));

  let child: ChildProcess | null = null;
  try {
    child = spawn(process.execPath, ["--import", "tsx", "src/server.ts", "--config", cfgPath], {
      cwd: SERVER_DIR,
      stdio: ["ignore", "pipe", "pipe"],
    });

    let stdout = "";
    let stderr = "";
    child.stdout!.on("data", (c) => (stdout += c.toString()));
    child.stderr!.on("data", (c) => (stderr += c.toString()));

    // 等就绪行，从里面抠真实端口
    const port = await new Promise<number>((resolve, reject) => {
      const t0 = Date.now();
      const timer = setInterval(() => {
        const m = stdout.match(/http:\/\/127\.0\.0\.1:(\d+)/);
        if (m) {
          clearInterval(timer);
          resolve(Number(m[1]));
        } else if (child!.exitCode !== null && child!.exitCode !== undefined) {
          clearInterval(timer);
          reject(new Error(`服务提前退出 code=${child!.exitCode} stderr=${stderr}`));
        } else if (Date.now() - t0 > 20000) {
          clearInterval(timer);
          reject(new Error(`20s 未见就绪行 stdout=${stdout} stderr=${stderr}`));
        }
      }, 100);
    });

    const health = await fetch(`http://127.0.0.1:${port}/health`);
    assert.equal(health.status, 200);
    const hj = await health.json();
    assert.equal(hj.ok, true);

    const manifest = await fetch(`http://127.0.0.1:${port}/manifest`);
    assert.equal(manifest.status, 200);
    const mj = await manifest.json();
    assert.equal(mj.protocol, 2);
    assert.ok(mj.files.some((f: any) => f.path === "mods/AAA-1.0.jar"));

    // 起来后应该还在（这正是那个入口 bug 的判据）
    assert.equal(child.exitCode, null);
  } finally {
    if (child && child.exitCode === null) child.kill();
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});
