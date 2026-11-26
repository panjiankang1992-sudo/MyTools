import { BusinessError } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import fs from '@ohos.file.fs';
import fileUri from '@ohos.file.fileuri';
import { ArchiveTree } from './ArchiveTree';
import fileHash from '@ohos.file.hash';
import cryptoFramework from '@ohos.security.cryptoFramework';
import { util } from '@kit.ArkTS';
import archiveEngine from 'libarchive_engine.so';
import { ArchiveEntry, ArchivePassword, ArchiveResult, ArchiveProgress } from './ArchiveModels';
import { ArchivePasswordStore } from './ArchivePasswordStore';
import { ArchivePasswordPolicy } from './ArchivePasswordPolicy';

export interface ArchiveOutput {
  path: string;
  source: string;
  size: number;
}
export interface ArchiveHistory {
  id: string;
  name: string;
  createdAt: number;
  outputs: ArchiveOutput[];
  directories?: string[];
}
export interface ArchiveUpdate {
  candidate: number;
  candidates: number;
  file: number;
  files: number;
  bytes: number;
  total: number;
  savingFailed: boolean;
}

/** 管理解压任务的生命周期、有限密码轮询与结果发布。 */
export class ArchiveJobService {
  readonly passwords: ArchivePasswordStore = new ArchivePasswordStore();
  private context: common.UIAbilityContext | undefined = undefined;
  private workspace: string = '';
  private source: string = '';
  private fingerprint: string = '';
  private historyRoot: string = '';
  private jobId: string = '';
  private cancelled: boolean = false;
  private lastPassword: string = '';
  private update: ArchiveUpdate = { candidate: 0, candidates: 0, file: 0, files: 0, bytes: 0, total: 0, savingFailed: false };
  onUpdate: (value: ArchiveUpdate) => void = () => {};
  onPause: () => Promise<boolean> = async () => false;

  /** 初始化本机账户空间。 */
  async initialize(context: common.UIAbilityContext, scope: string): Promise<void> {
    try {
    this.context = context;
    await this.passwords.initialize(scope);
    // 文件名使用不可逆摘要，账户名不进入磁盘路径。
    const digest = cryptoFramework.createMd('SHA256');
    await digest.update({ data: new util.TextEncoder().encodeInto(scope) });
    const key = Array.from((await digest.digest()).data).map((value: number) => value.toString(16).padStart(2, '0')).join('');
    this.historyRoot = `${context.filesDir}/archive-results/${key || 'local'}`;
    if (!fs.accessSync(this.historyRoot)) fs.mkdirSync(this.historyRoot, true);
    } catch (error) {
      console.info(`Archive SDK failure: ${(error as BusinessError).code ?? -1}`);
      throw new Error(error instanceof Error && /^ARCHIVE_/.test(error.message) ? error.message : 'ARCHIVE_008');
    }
  }

  /** 将用户授权的压缩包复制到独立缓存，并计算内容指纹。 */
  async prepare(uri: string): Promise<void> {
    try {
    this.cleanup(); this.cancelled = false;
    if (!this.context) throw new Error('ARCHIVE_011');
    const stat = fs.statSync(uri);
    if (!stat.isFile() || stat.size <= 0 || stat.size > 500 * 1024 * 1024) throw new Error('ARCHIVE_006');
    this.workspace = `${this.context.cacheDir}/archive-job-${util.generateRandomUUID()}`;
    fs.mkdirSync(this.workspace);
    this.source = `${this.workspace}/input`;
    let input: fs.File | undefined = undefined;
    let output: fs.File | undefined = undefined;
    try {
      input = fs.openSync(uri, fs.OpenMode.READ_ONLY);
      output = fs.openSync(this.source, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      await fs.copyFile(input.fd, output.fd);
    } finally {
      if (input) fs.closeSync(input);
      if (output) fs.closeSync(output);
    }
    if (this.cancelled) { this.cleanup(); throw new Error('ARCHIVE_010'); }
    this.fingerprint = await fileHash.hash(this.source, 'sha256');
    // 指纹计算也是异步阶段，结束后必须再次尊重取消，不能重新启动预览。
    if (this.cancelled) { this.cleanup(); throw new Error('ARCHIVE_010'); }
    this.lastPassword = '';
    } catch (error) {
      console.info(`Archive SDK failure: ${(error as BusinessError).code ?? -1}`);
      throw new Error(error instanceof Error && /^ARCHIVE_/.test(error.message) ? error.message : 'ARCHIVE_008');
    }
  }

  /** 预览压缩包，必要时使用历史密码解锁目录。 */
  async inspect(manual: string, automatic: boolean): Promise<ArchiveResult> {
    this.cancelled = false;
    let result = await this.match(false, [], '', manual, automatic);
    // 只自动展开 GZIP 外壳中的 TAR；普通嵌套压缩包仍由用户决定。
    if (result.code.length === 0 && result.format === 'GZIP' && result.entries.length === 1) {
      const directory = `${this.workspace}/gzip-${util.generateRandomUUID()}`;
      const expanded = await this.match(true, [result.entries[0].id], directory, '', false);
      if (expanded.code.length > 0) return expanded;
      if (expanded.entries.length === 1) {
        const original = this.source;
        this.source = `${directory}/${expanded.entries[0].path}`;
        const inner = await this.match(false, [], '', '', false);
        if (inner.code.length === 0 && inner.format === 'TAR') result = inner;
        else this.source = original;
      }
    }
    return result;
  }

  /** 按文件校验密码，支持同一个 ZIP 的不同文件使用不同密码。 */
  async extract(entries: ArchiveEntry[], manual: string, automatic: boolean, name: string): Promise<ArchiveHistory> {
    this.cancelled = false;
    const files = entries.filter((entry: ArchiveEntry) => !entry.directory);
    const directories = entries.filter((entry: ArchiveEntry) => entry.directory).map((entry: ArchiveEntry) => ArchiveTree.safePath(entry.path));
    if (entries.length === 0) throw new Error('ARCHIVE_011');
    const id = util.generateRandomUUID();
    const staging = `${this.workspace}/result-${id}`;
    try { fs.mkdirSync(staging); } catch (_) { throw new Error('ARCHIVE_008'); }
    const outputs: ArchiveOutput[] = [];
    let total = 0;
    try {
      for (let index = 0; index < files.length; index++) {
        this.update.file = index + 1; this.update.files = files.length;
        const directory = `${staging}/part-${index}`;
        const result = await this.match(true, [files[index].id], directory, manual, automatic);
        if (result.code.length > 0) throw new Error(result.code);
        // 逐文件调用仍共享一个任务总额度，不能累加绕过限制。
        for (const item of result.entries) {
          total += item.size;
          if (total > 300 * 1024 * 1024) throw new Error('ARCHIVE_006');
          outputs.push({ path: item.path, source: `part-${index}/${item.path}`, size: item.size });
        }
      }
      if (this.cancelled) throw new Error('ARCHIVE_010');
      const final = `${this.historyRoot}/${id}`;
      await fs.rename(staging, final);
      const history: ArchiveHistory = { id, name, createdAt: Date.now(), outputs, directories };
      let record: fs.File | undefined = undefined;
      try {
        record = fs.openSync(`${final}/record.json`, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY);
        fs.writeSync(record.fd, JSON.stringify(history));
      } catch (_) {
        this.removeDirectory(final); throw new Error('ARCHIVE_008');
      } finally { if (record) fs.closeSync(record); }
      return history;
    } catch (error) { this.removeDirectory(staging); throw new Error(error instanceof Error ? error.message : 'ARCHIVE_011'); }
    finally { this.lastPassword = ''; }
  }

  private async match(extract: boolean, ids: number[], output: string, manual: string, automatic: boolean): Promise<ArchiveResult> {
    let records: ArchivePassword[] = [];
    this.update.savingFailed = false;
    try { records = await this.passwords.load(); }
    catch (_) { this.update.savingFailed = true; }
    const known = this.lastPassword.length > 0 ? records.find((item: ArchivePassword) => item.value === this.lastPassword) : undefined;
    if (known) known.fingerprint = this.fingerprint;
    const queue = ArchivePasswordPolicy.candidates(automatic ? records : records.filter((item: ArchivePassword) =>
      item.value === manual || item.value === this.lastPassword), manual.length > 0 ? manual : this.lastPassword, this.fingerprint);
    // 无密码探测避免为普通文件误记用户未使用的密码。
    const empty: ArchivePassword = { id: '', value: '', createdAt: 0, lastUsedAt: 0, attempts: 0, successes: 0, fingerprint: '' };
    queue.unshift(empty);
    this.update.candidates = queue.length - 1;
    let started = Date.now();
    let result: ArchiveResult = { code: 'ARCHIVE_002', format: '', entries: [], bytes: 0, passwordUsed: false };
    for (let index = 0; index < queue.length; index++) {
      if (this.cancelled) throw new Error('ARCHIVE_010');
      // 时间片结束后等待用户继续，候选游标保持不变。
      if (index > 0 && Date.now() - started >= 30000) {
        if (!await this.onPause()) throw new Error('ARCHIVE_010');
        started = Date.now();
      }
      const candidate = queue[index];
      this.update.candidate = index;
      this.jobId = util.generateRandomUUID();
      this.onUpdate(this.update);
      const timer = setInterval(() => {
        try {
          const progress = JSON.parse(archiveEngine.status(this.jobId, false)) as ArchiveProgress;
          this.update.bytes = progress.bytes; this.update.total = progress.total; this.onUpdate(this.update);
        } catch (_) { /* 已完成的任务可能已经释放。 */ }
      }, 180);
      try {
        result = JSON.parse(await archiveEngine.run(this.jobId, this.source, output, candidate.value, extract, ids, 1)) as ArchiveResult;
        console.info(`Archive native result: ${result.code}`);
      } finally { clearInterval(timer); this.jobId = ''; }
      if (candidate.value.length > 0 && result.passwordUsed) {
        try { await this.passwords.record(candidate, result.code.length === 0 && extract, this.fingerprint, true); }
        catch (_) { this.update.savingFailed = true; }
        this.onUpdate(this.update);
      }
      if (this.cancelled) throw new Error('ARCHIVE_010');
      if (result.code.length === 0) { if (result.passwordUsed) this.lastPassword = candidate.value; return result; }
      // 校验失败可能是密码错误或包损坏，可以试完已有候选；其他错误必须立即停止。
      if (!['ARCHIVE_002', 'ARCHIVE_003', 'ARCHIVE_004'].includes(result.code) ||
        (result.code === 'ARCHIVE_004' && !result.passwordUsed)) return result;
    }
    return result;
  }

  /** 请求原生解码器停止，等待当前回调退出后再清理。 */
  cancel(): void {
    this.cancelled = true; this.lastPassword = '';
    if (this.jobId.length > 0) {
      try { archiveEngine.status(this.jobId, true); } catch (_) { /* 任务可能刚好结束。 */ }
    }
  }

  /** 清除输入与失败中间文件，已完成结果保留。 */
  cleanup(): void {
    if (this.workspace.length > 0) this.removeDirectory(this.workspace);
    this.workspace = ''; this.source = ''; this.lastPassword = '';
  }

  /** 从当前账户的完整任务目录读取记录。 */
  history(): ArchiveHistory[] {
    const result: ArchiveHistory[] = [];
    try {
      for (const id of fs.listFileSync(this.historyRoot)) {
        if (!/^[a-f0-9-]{36}$/.test(id)) continue;
        try {
          const row = JSON.parse(fs.readTextSync(`${this.historyRoot}/${id}/record.json`)) as ArchiveHistory;
          if (row.id === id && Array.isArray(row.outputs)) result.push(row);
        } catch (_) { /* 不显示未发布完成的任务。 */ }
      }
    } catch (_) { return []; }
    return result.sort((a: ArchiveHistory, b: ArchiveHistory) => b.createdAt - a.createdAt);
  }

  /** 导出已完成且未变化的普通文件到系统授权位置。 */
  async exportFile(history: ArchiveHistory, output: ArchiveOutput, destination: string): Promise<void> {
    try {
    const trusted = this.history().find((item: ArchiveHistory) => item.id === history.id);
    if (!trusted || !trusted.outputs.some((item: ArchiveOutput) => item.source === output.source && item.size === output.size)) {
      throw new Error('ARCHIVE_008');
    }
    ArchiveTree.safePath(output.source);
    const path = `${this.historyRoot}/${history.id}/${output.source}`;
    const stat = fs.lstatSync(path);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size !== output.size) throw new Error('ARCHIVE_004');
    let input: fs.File | undefined = undefined;
    let outputFile: fs.File | undefined = undefined;
    try {
      input = fs.openSync(path, fs.OpenMode.READ_ONLY);
      outputFile = fs.openSync(destination, fs.OpenMode.WRITE_ONLY | fs.OpenMode.TRUNC);
      await fs.copyFile(input.fd, outputFile.fd);
    } finally {
      if (input) fs.closeSync(input);
      if (outputFile) fs.closeSync(outputFile);
    }
    } catch (error) {
      console.info(`Archive SDK failure: ${(error as BusinessError).code ?? -1}`);
      throw new Error(error instanceof Error && /^ARCHIVE_/.test(error.message) ? error.message : 'ARCHIVE_008');
    }
  }

  /** 导出一次授权的多个文件，逐项报告实际完成数。 */
  async exportFiles(history: ArchiveHistory, destinations: string[], onProgress: (done: number, total: number) => void): Promise<void> {
    if (destinations.length !== history.outputs.length || destinations.some((uri: string) => uri.length === 0) ||
      new Set<string>(destinations).size !== destinations.length) throw new Error('ARCHIVE_008');
    this.cancelled = false;
    onProgress(0, history.outputs.length);
    for (let index = 0; index < history.outputs.length; index++) {
      if (this.cancelled) throw new Error('ARCHIVE_010');
      await this.exportFile(history, history.outputs[index], destinations[index]);
      onProgress(index + 1, history.outputs.length);
    }
  }

  /** 在已授权的目标目录中创建独立结果文件夹，失败只回滚本次新建内容。 */
  async exportDirectory(history: ArchiveHistory, destination: string, onProgress: (done: number, total: number) => void): Promise<string> {
    const trusted = this.history().find((item: ArchiveHistory) => item.id === history.id);
    if (!trusted) throw new Error('ARCHIVE_008');
    this.cancelled = false;
    const parent = new fileUri.FileUri(destination).path;
    const root = `${parent}/MyTools-${util.generateRandomUUID()}`;
    let owned = false;
    try {
      // 根目录必须由本次新建，不能复用用户已有目录。
      fs.mkdirSync(root); owned = true;
      for (const directory of trusted.directories || []) {
        const target = `${root}/${ArchiveTree.safePath(directory)}`;
        if (!fs.accessSync(target)) fs.mkdirSync(target, true);
      }
      onProgress(0, trusted.outputs.length);
      for (let index = 0; index < trusted.outputs.length; index++) {
        if (this.cancelled) throw new Error('ARCHIVE_010');
        const output = trusted.outputs[index];
        const target = `${root}/${ArchiveTree.safePath(output.path)}`;
        const directory = target.substring(0, target.lastIndexOf('/'));
        if (!fs.accessSync(directory)) fs.mkdirSync(directory, true);
        if (fs.accessSync(target)) throw new Error('ARCHIVE_009');
        const file = fs.openSync(target, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY);
        fs.closeSync(file);
        await this.exportFile(trusted, output, target);
        onProgress(index + 1, trusted.outputs.length);
      }
      if (this.cancelled) throw new Error('ARCHIVE_010');
      return root.split('/').pop() || '';
    } catch (error) {
      if (owned) this.removeDirectory(root);
      throw new Error(error instanceof Error && /^ARCHIVE_/.test(error.message) ? error.message : 'ARCHIVE_008');
    }
  }

  /** 删除用户选中的解压结果与任务记录。 */
  removeHistory(id: string): void {
    if (!/^[a-f0-9-]{36}$/.test(id)) throw new Error('ARCHIVE_009');
    try { fs.rmdirSync(`${this.historyRoot}/${id}`); } catch (_) { throw new Error('ARCHIVE_008'); }
  }

  private removeDirectory(path: string): void {
    try { fs.rmdirSync(path); } catch (_) { /* 目录可能在错误恢复中已被删除。 */ }
  }
}
