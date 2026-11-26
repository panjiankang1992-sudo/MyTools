import type { AdminExportManifest, AdminExportPackage, AdminExportPackageVersion } from '../services/admin-curriculum-api';

/**
 * 恢复前的**本地预检**：把"将要从这个包恢复什么"变成可展示、可判定的摘要。
 *
 * 为什么要有这一层：恢复是破坏性操作，用户必须在**点确认之前**看到册数、版本数与
 * 已知冲突。这些判断里有一部分（包结构、字节长度、包内重复）**只依赖包本身**，
 * 拿到库内现状清单后还能多做一步"同版本是否已存在、哈希是否一致"的比对。
 *
 * **边界（写清楚，不假装）**：
 * - 本模块**不计算 SHA-256**。浏览器端 `crypto.subtle` 在 jsdom 下不保证存在，
 *   而且逐版哈希一个大包在主线程上很贵。服务端在写入前会自行校验
 *   Base64 解码后的**长度与哈希**（`contentHashOrLengthMismatch`），本层只做
 *   **长度**这一项低成本预检——它足以拦住"粘错包/被截断的包"。
 * - 拿不到库内清单时（`manifest === null`），`comparedWithDatabase` 为 false，
 *   冲突判定退化为**包内自查**，界面必须如实说明这一点，不能显示成"无冲突"。
 */

/** 预检判定。 */
export type RestoreVerdict = 'new' | 'skip' | 'conflict';

/** 预检结果里的一项（一册的一个版本）。 */
export interface RestorePreviewEntry {
  editionId: string;
  contentVersion: string;
  current: boolean;
  verdict: RestoreVerdict;
  /** 面向用户的判定说明。 */
  reason: string;
}

/** 预检摘要。 */
export interface RestorePreview {
  /** 涉及册数（去重后的 editionId 数）。 */
  editionCount: number;
  /** 版本总数。 */
  versionCount: number;
  /** 包内标为 current 的版本数。 */
  currentVersionCount: number;
  entries: RestorePreviewEntry[];
  /** 判定为冲突的项；调用方必须展示，不得静默跳过。 */
  conflicts: RestorePreviewEntry[];
  /** 是否已与库内现状比对过。 */
  comparedWithDatabase: boolean;
}

/** 解析结果。 */
export type ExportPackageParseResult =
  | { ok: true; pkg: AdminExportPackage }
  | { ok: false; error: string };

/** 导出包格式标识；与后端 `buildExportPackage` 写出的值一致。 */
const PACKAGE_FORMAT = 'toolbox-curriculum-package';

/** 是否为普通对象。 */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 取一个字符串字段；缺失时返回空串。 */
function text(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

/**
 * 解析用户粘贴或选中的导出包 JSON。
 *
 * 只做**结构**校验：格式标识、versions 非空、每项有 editionId 与 contentVersion。
 * 内容是否可信由服务端校验，这里不越权代替它做哈希判断。
 *
 * @param raw 原始 JSON 文本
 * @returns 解析后的包，或一句人能看懂的错误
 */
export function parseExportPackage(raw: string): ExportPackageParseResult {
  const trimmed = raw.trim();
  if (!trimmed) return { ok: false, error: '请先粘贴或选择导出包 JSON。' };
  let parsed: unknown;
  try {
    parsed = JSON.parse(trimmed);
  } catch (cause) {
    return { ok: false, error: `导出包不是合法 JSON：${cause instanceof Error ? cause.message : '解析失败'}` };
  }
  if (!isRecord(parsed)) return { ok: false, error: '导出包必须是一个 JSON 对象。' };
  const format = text(parsed.format);
  if (format !== PACKAGE_FORMAT) {
    return { ok: false, error: `这不是工具箱导出包：format 应为 ${PACKAGE_FORMAT}，实际为 ${format || '（缺失）'}。` };
  }
  if (!Array.isArray(parsed.versions) || parsed.versions.length === 0) {
    return { ok: false, error: '导出包内没有任何版本（versions 为空）。' };
  }
  const versions: AdminExportPackageVersion[] = [];
  for (let index = 0; index < parsed.versions.length; index += 1) {
    const item: unknown = parsed.versions[index];
    if (!isRecord(item)) return { ok: false, error: `导出包第 ${index + 1} 项不是对象。` };
    const editionId = text(item.editionId);
    const contentVersion = text(item.contentVersion);
    if (!editionId || !contentVersion) {
      return { ok: false, error: `导出包第 ${index + 1} 项缺少 editionId 或 contentVersion。` };
    }
    versions.push({
      editionId,
      contentVersion,
      contentSha256: text(item.contentSha256),
      byteLength: typeof item.byteLength === 'number' ? item.byteLength : -1,
      contentBase64: text(item.contentBase64),
      current: item.current === true,
      editionMetadataJson: typeof item.editionMetadataJson === 'string' ? item.editionMetadataJson : null,
      sourceMetadataJson: typeof item.sourceMetadataJson === 'string' ? item.sourceMetadataJson : null,
      sourceSnapshotSha256: typeof item.sourceSnapshotSha256 === 'string' ? item.sourceSnapshotSha256 : null
    });
  }
  return {
    ok: true,
    pkg: {
      format,
      formatVersion: typeof parsed.formatVersion === 'number' ? parsed.formatVersion : 0,
      exportedBy: text(parsed.exportedBy),
      exportedAt: text(parsed.exportedAt),
      versionCount: typeof parsed.versionCount === 'number' ? parsed.versionCount : versions.length,
      packageSha256: text(parsed.packageSha256),
      versions
    }
  };
}

/**
 * 由 Base64 文本推算原始字节长度，**不解码**。
 *
 * 大包逐项 `atob` 会在主线程上白烧时间，而长度用算术就能得到：
 * 去掉补位符后，每 4 个字符对应 3 字节。
 *
 * @param base64 Base64 文本
 * @returns 字节长度；非法字符时返回 -1
 */
export function base64ByteLength(base64: string): number {
  if (!base64) return -1;
  // 只接受标准 Base64 字母表与补位符：非法输入不应被折算成一个"看起来对"的长度。
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(base64)) return -1;
  const stripped = base64.replace(/=+$/, '');
  return Math.floor((stripped.length * 3) / 4);
}

/** 从库内清单建立 `editionId@contentVersion → 哈希` 索引（哈希统一小写）。 */
function buildExistingIndex(manifest: AdminExportManifest | null): Map<string, string> {
  const index = new Map<string, string>();
  if (!manifest?.editions) return index;
  for (const edition of manifest.editions) {
    for (const version of edition.history ?? []) {
      index.set(`${edition.editionId}@${version.contentVersion}`, (version.contentSha256 ?? '').toLowerCase());
    }
  }
  return index;
}

/**
 * 生成恢复前摘要。
 *
 * @param pkg      已解析的导出包
 * @param manifest 库内现状；为 null 表示没读到，此时只做包内自查
 * @returns 摘要（含冲突清单）
 */
export function buildRestorePreview(
  pkg: AdminExportPackage,
  manifest: AdminExportManifest | null
): RestorePreview {
  const existing = buildExistingIndex(manifest);
  const seen = new Set<string>();
  const entries: RestorePreviewEntry[] = [];

  for (const version of pkg.versions) {
    const key = `${version.editionId}@${version.contentVersion}`;
    const declaredHash = version.contentSha256.toLowerCase();
    let verdict: RestoreVerdict;
    let reason: string;
    const actualLength = base64ByteLength(version.contentBase64);

    if (!version.contentBase64 || !version.contentSha256) {
      verdict = 'conflict';
      reason = '条目缺少正文或哈希，服务端会拒绝该版本。';
    } else if (actualLength < 0) {
      verdict = 'conflict';
      reason = '正文不是合法的 Base64，服务端会拒绝该版本。';
    } else if (version.byteLength >= 0 && actualLength !== version.byteLength) {
      verdict = 'conflict';
      reason = `正文长度与声明不符（声明 ${version.byteLength} 字节，实际 ${actualLength} 字节），服务端会拒绝该版本。`;
    } else if (seen.has(key)) {
      // 服务端会逐条处理：同哈希的第二条被跳过、异哈希的第二条报冲突。
      // 无论哪种，"包里同一版本出现两次"都值得在动手前说清楚。
      verdict = 'conflict';
      reason = '包内重复：同一册同一版本在包内出现多次，结果取决于两条的哈希是否一致。';
    } else if (existing.has(key)) {
      if (existing.get(key) === declaredHash) {
        verdict = 'skip';
        reason = '库内已有完全相同的版本，将跳过（不新增、不改动）。';
      } else {
        verdict = 'conflict';
        reason = '库内已有同一版本但内容不同，会报冲突且不会被覆盖，需人工判断。';
      }
    } else {
      verdict = 'new';
      reason = '库内没有这个版本，将会新增。';
    }

    seen.add(key);
    entries.push({ editionId: version.editionId, contentVersion: version.contentVersion, current: version.current, verdict, reason });
  }

  return {
    editionCount: new Set(pkg.versions.map(item => item.editionId)).size,
    versionCount: pkg.versions.length,
    currentVersionCount: pkg.versions.filter(item => item.current).length,
    entries,
    conflicts: entries.filter(item => item.verdict === 'conflict'),
    comparedWithDatabase: manifest !== null
  };
}
