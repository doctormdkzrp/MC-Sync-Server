import * as zlib from "node:zlib";

export interface JarMeta {
  name?: string;
  desc?: string;
  modId?: string;
  version?: string;
  source?: string;
}

/** 极简 ZIP 读取（jar 就是 zip）：只按中央目录取指定条目的内容。 */
export function zipRead(buffer: Buffer, wantedNames: string[]): Map<string, Buffer> {
  const out = new Map<string, Buffer>();
  const want = new Set(wantedNames.map((n) => n.toLowerCase()));

  // 找 EOCD（从尾部往前找签名 0x06054b50）
  let eocd = -1;
  const minStart = Math.max(0, buffer.length - 65557);
  for (let i = buffer.length - 22; i >= minStart; i--) {
    if (buffer.readUInt32LE(i) === 0x06054b50) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) return out;

  const cdCount = buffer.readUInt16LE(eocd + 10);
  const cdOffset = buffer.readUInt32LE(eocd + 16);
  let p = cdOffset;
  for (let n = 0; n < cdCount && p + 46 <= buffer.length; n++) {
    if (buffer.readUInt32LE(p) !== 0x02014b50) break;
    const method = buffer.readUInt16LE(p + 10);
    const compSize = buffer.readUInt32LE(p + 20);
    const nameLen = buffer.readUInt16LE(p + 28);
    const extraLen = buffer.readUInt16LE(p + 30);
    const commentLen = buffer.readUInt16LE(p + 32);
    const localOffset = buffer.readUInt32LE(p + 42);
    const name = buffer.toString("utf-8", p + 46, p + 46 + nameLen);
    p += 46 + nameLen + extraLen + commentLen;

    if (!want.has(name.toLowerCase())) continue;
    if (compSize === 0xffffffff || localOffset === 0xffffffff) continue; // zip64，跳过
    // 本地头
    if (buffer.readUInt32LE(localOffset) !== 0x04034b50) continue;
    const lNameLen = buffer.readUInt16LE(localOffset + 26);
    const lExtraLen = buffer.readUInt16LE(localOffset + 28);
    const dataStart = localOffset + 30 + lNameLen + lExtraLen;
    const raw = buffer.subarray(dataStart, dataStart + compSize);
    try {
      out.set(name, method === 0 ? Buffer.from(raw) : zlib.inflateRawSync(raw));
    } catch {
      /* 坏条目忽略 */
    }
  }
  return out;
}

/** 从 jar 里尽力读出「名字 + 简介」：fabric.mod.json → mcmod.info → MANIFEST.MF。 */
export function readJarMeta(buffer: Buffer): JarMeta {
  const files = zipRead(buffer, ["fabric.mod.json", "mcmod.info", "META-INF/MANIFEST.MF"]);

  const fabric = files.get("fabric.mod.json");
  if (fabric) {
    try {
      const j = JSON.parse(fabric.toString("utf-8"));
      if (j && (j.name || j.description)) {
        return {
          name: j.name ?? j.id,
          desc: typeof j.description === "string" ? j.description : undefined,
          modId: j.id,
          version: j.version,
          source: "fabric.mod.json",
        };
      }
    } catch {
      /* 继续往下试 */
    }
  }

  const mcmod = files.get("mcmod.info");
  if (mcmod) {
    try {
      let j: any = JSON.parse(mcmod.toString("utf-8"));
      if (j && !Array.isArray(j) && Array.isArray(j.modList)) j = j.modList;
      const first = Array.isArray(j) ? j[0] : j;
      if (first && (first.name || first.description)) {
        return {
          name: first.name ?? first.modid,
          desc: typeof first.description === "string" ? first.description : undefined,
          modId: first.modid,
          version: first.version,
          source: "mcmod.info",
        };
      }
    } catch {
      /* 手写 mcmod.info 很常见（非严格 JSON），忽略 */
    }
  }

  const mf = files.get("META-INF/MANIFEST.MF");
  if (mf) {
    const text = mf.toString("utf-8");
    const get = (key: string): string | undefined => {
      const m = text.match(new RegExp(`^${key}:\\s*(.+)$`, "mi"));
      return m?.[1]?.trim() || undefined;
    };
    const name = get("Implementation-Title") ?? get("Specification-Title");
    const desc = get("Implementation-Vendor") ?? get("Specification-Vendor");
    const version = get("Implementation-Version");
    if (name || desc) return { name, desc, version, source: "MANIFEST.MF" };
  }

  return {};
}

/** 从文件名兜底猜个名字：去掉版本号和扩展名。 */
export function nameFromFile(file: string): string {
  return file
    .replace(/\.jar$/i, "")
    .replace(/[-_](?:\d+\.)+\d+.*$/, "")
    .replace(/[-_]?(?:mc)?1\.\d+(?:\.\d+)?.*$/i, "")
    .trim() || file;
}
