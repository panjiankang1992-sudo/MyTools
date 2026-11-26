import { VehicleBleDevice, VehicleBleStandardInfo, VehicleBleStandardRead } from './VehicleBleModels';

/** 蓝牙验证模块的输入边界和标准只读协议策略。 */
export class VehicleBlePolicy {
  static readonly MAX_DEVICES: number = 40;
  static readonly MAX_SERVICES: number = 32;
  static readonly MAX_CHARACTERISTICS: number = 256;
  static readonly SCAN_DURATION_MS: number = 12000;
  static readonly CONNECT_TIMEOUT_MS: number = 12000;
  static readonly DISCOVERY_TIMEOUT_MS: number = 8000;
  static readonly READ_TIMEOUT_MS: number = 2500;
  private static readonly BASE_SUFFIX: string = '-0000-1000-8000-00805f9b34fb';
  private static readonly YADEA_SERVICE: string = '0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc';

  /** 规范化标准短 UUID 和完整 UUID，无效输入返回空值。 */
  static uuid(value: string): string {
    if (typeof value !== 'string') return '';
    const normalized = value.trim().toLowerCase();
    if (/^[0-9a-f]{4}$/.test(normalized)) return `0000${normalized}${VehicleBlePolicy.BASE_SUFFIX}`;
    if (/^[0-9a-f]{8}$/.test(normalized)) return `${normalized}${VehicleBlePolicy.BASE_SUFFIX}`;
    return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(normalized) ? normalized : '';
  }

  /** 只接受系统返回的蓝牙地址形态，地址仍不能作为永久车辆身份。 */
  static deviceId(value: string): boolean {
    return typeof value === 'string' && /^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$/.test(value);
  }

  /** 清理广播名称中的控制字符和方向控制字符，并限制显示长度。 */
  static text(value: string, maximum: number = 64): string {
    if (typeof value !== 'string') return '';
    return value.replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, '').trim().substring(0, maximum);
  }

  /** 名称和广播中的服务只能提示候选，不授予车辆控制权限。 */
  static yadeaCandidate(name: string, advertisement: Uint8Array): boolean {
    if (/(yadea|yadi|smartmoto|q9)/i.test(name) || name.includes(String.fromCharCode(0x96c5, 0x8fea))) return true;
    if (advertisement.length > 1650) return false;
    // 标准广播字段按长度逐个读取，遇到截断字段立即停止。
    let offset = 0;
    while (offset < advertisement.length) {
      const size = advertisement[offset];
      if (size === 0) break;
      if (size < 1 || offset + size >= advertisement.length) break;
      const type = advertisement[offset + 1];
      if (type === 0x06 || type === 0x07) {
        for (let index = offset + 2; index + 16 <= offset + size + 1; index += 16) {
          let hex = '';
          for (let byte = index + 15; byte >= index; byte--) hex += advertisement[byte].toString(16).padStart(2, '0');
          const uuid = `${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}`;
          if (uuid === VehicleBlePolicy.YADEA_SERVICE) return true;
        }
      }
      offset += size + 1;
    }
    return false;
  }

  /** 按同轮系统地址去重，保留已配对标记并限制设备总数。 */
  static mergeDevice(current: VehicleBleDevice[], incoming: VehicleBleDevice): VehicleBleDevice[] {
    if (!VehicleBlePolicy.deviceId(incoming.id)) return current.slice(0, VehicleBlePolicy.MAX_DEVICES);
    const rows = current.slice(0, VehicleBlePolicy.MAX_DEVICES);
    const existing = rows.findIndex((row: VehicleBleDevice): boolean => row.id.toLowerCase() === incoming.id.toLowerCase());
    const previous = existing >= 0 ? rows[existing] : undefined;
    const row: VehicleBleDevice = {
      id: incoming.id,
      name: VehicleBlePolicy.text(incoming.name.length > 0 ? incoming.name : previous?.name ?? ''),
      paired: incoming.paired || previous?.paired === true,
      connectable: incoming.connectable,
      rssi: incoming.rssi !== undefined && Number.isFinite(incoming.rssi) && incoming.rssi >= -127 && incoming.rssi <= 20 ? incoming.rssi : previous?.rssi,
      lastSeenAt: Number.isFinite(incoming.lastSeenAt) && incoming.lastSeenAt >= 0 ? incoming.lastSeenAt : 0,
      yadeaCandidate: incoming.yadeaCandidate || previous?.yadeaCandidate === true
    };
    if (existing >= 0) rows[existing] = row;
    else if (rows.length < VehicleBlePolicy.MAX_DEVICES) rows.push(row);
    else if (row.yadeaCandidate) {
      // 满额后仍允许新车辆候选替换无候选特征的设备，避免车库杂项占满名额。
      const replace = rows.findIndex((candidate: VehicleBleDevice): boolean => !candidate.yadeaCandidate && !candidate.paired);
      if (replace >= 0) rows[replace] = row;
    }
    return rows;
  }

  /** 返回允许读取的标准字段，拒绝序列号、私有服务和任意特征。 */
  static standardRead(service: string, characteristic: string): VehicleBleStandardRead | undefined {
    const serviceUuid = VehicleBlePolicy.uuid(service);
    const characteristicUuid = VehicleBlePolicy.uuid(characteristic);
    let field = '';
    if (serviceUuid === VehicleBlePolicy.uuid('180a')) {
      if (characteristicUuid === VehicleBlePolicy.uuid('2a29')) field = 'manufacturer';
      else if (characteristicUuid === VehicleBlePolicy.uuid('2a24')) field = 'model';
      else if (characteristicUuid === VehicleBlePolicy.uuid('2a26')) field = 'firmware';
      else if (characteristicUuid === VehicleBlePolicy.uuid('2a27')) field = 'hardware';
      else if (characteristicUuid === VehicleBlePolicy.uuid('2a28')) field = 'software';
    } else if (serviceUuid === VehicleBlePolicy.uuid('180f') && characteristicUuid === VehicleBlePolicy.uuid('2a19')) {
      field = 'batteryLevel';
    }
    return field.length > 0 ? { serviceUuid, characteristicUuid, field } : undefined;
  }

  /** 生成未知的标准信息，避免将默认值显示为真实车辆数据。 */
  static emptyInfo(): VehicleBleStandardInfo {
    return { manufacturer: '', model: '', firmware: '', hardware: '', software: '', batteryLevel: undefined, observedAt: 0 };
  }

  /** 限量解析标准 UTF-8 文本或单字节电量，非法值不进入状态。 */
  static applyStandardValue(info: VehicleBleStandardInfo, read: VehicleBleStandardRead, bytes: Uint8Array, now: number): boolean {
    const allowed = VehicleBlePolicy.standardRead(read.serviceUuid, read.characteristicUuid);
    if (allowed === undefined || allowed.field !== read.field || !Number.isFinite(now) || now <= 0) return false;
    if (read.field === 'batteryLevel') {
      if (bytes.length !== 1 || bytes[0] > 100) return false;
      info.batteryLevel = bytes[0];
    } else {
      const value = VehicleBlePolicy.standardText(bytes);
      if (value.length === 0) return false;
      if (read.field === 'manufacturer') info.manufacturer = value;
      else if (read.field === 'model') info.model = value;
      else if (read.field === 'firmware') info.firmware = value;
      else if (read.field === 'hardware') info.hardware = value;
      else if (read.field === 'software') info.software = value;
      else return false;
    }
    info.observedAt = now;
    return true;
  }

  /** 只暴露本轮本机可用的地址后缀，不持久化或输出完整设备地址。 */
  static maskedId(value: string): string {
    return VehicleBlePolicy.deviceId(value) ? `**:**:**:**:${value.substring(value.length - 5)}` : '';
  }

  private static standardText(bytes: Uint8Array): string {
    if (bytes.length === 0 || bytes.length > 192) return '';
    // 百分号编码交由标准 UTF-8 解码器严格检查，避免宽松解码隐藏非法字节。
    let encoded = '';
    for (let index = 0; index < bytes.length; index++) encoded += '%' + bytes[index].toString(16).padStart(2, '0');
    try { return VehicleBlePolicy.text(decodeURIComponent(encoded), 96); }
    catch (_) { return ''; }
  }
}

/** 让停止、换设备和销毁之后的异步结果失效。 */
export class VehicleBleEpoch {
  private revision: number = 0;
  private disposed: boolean = false;

  /** 开始新一轮活动，同时让上一轮的回调失效。 */
  begin(): number { this.revision++; return this.revision; }

  /** 核验回调是否属于仍然有效的当前活动。 */
  current(revision: number): boolean { return !this.disposed && this.revision === revision; }

  /** 使当前活动失效，后续仍可显式开始新活动。 */
  invalidate(): void { this.revision++; }

  /** 永久关闭此实例，后续回调全部失效。 */
  close(): void { this.disposed = true; this.revision++; }
}
