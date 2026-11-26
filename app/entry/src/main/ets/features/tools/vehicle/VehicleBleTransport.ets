import { access, ble, connection, constant } from '@kit.ConnectivityKit';
import { VehicleBleDevice, VehicleBleService, VehicleBleCharacteristic, VehicleBleSnapshot,
  VehicleBleStandardInfo, VehicleBleStandardRead } from './VehicleBleModels';
import { VehicleBleEpoch, VehicleBlePolicy } from './VehicleBlePolicy';

/** 仅实现设备发现、连接和标准白名单读取，不发送车辆控制或猜测认证。 */
export class VehicleBleTransport {
  private readonly epoch: VehicleBleEpoch = new VehicleBleEpoch();
  private readonly observer: (snapshot: VehicleBleSnapshot) => void;
  private phase: string = 'IDLE';
  private devices: VehicleBleDevice[] = [];
  private services: VehicleBleService[] = [];
  private info: VehicleBleStandardInfo = VehicleBlePolicy.emptyInfo();
  private selectedDeviceId: string = '';
  private connected: boolean = false;
  private scanning: boolean = false;
  private error: string = '';
  private readErrorCount: number = 0;
  private disposed: boolean = false;
  private scanTimer: number = -1;
  private scanListener?: (rows: ble.ScanResult[]) => void;
  private client?: ble.GattClientDevice;
  private connectionListener?: (event: ble.BLEConnectionChangeState) => void;
  private pendingAbort?: () => void;
  private accessSubscribed: boolean = false;
  private readonly accessListener: (state: access.BluetoothState) => void = (state: access.BluetoothState): void => {
    if (this.disposed || this.bluetoothReady(state)) return;
    // 蓝牙关闭立即终止活动，使旧设备事件和读取结果无法回写。
    this.epoch.invalidate();
    this.cancelPending();
    this.stopScanInternal();
    this.releaseClient();
    this.phase = 'ERROR';
    this.error = 'VEHICLE_BLE_OFF';
    this.publish();
  };

  /** 创建仅在显式调用后使用蓝牙的传输实例。 */
  constructor(observer: (snapshot: VehicleBleSnapshot) => void) { this.observer = observer; }

  /** 返回当前状态的隔离副本，连接状态始终不代表车辆鉴权通过。 */
  snapshot(): VehicleBleSnapshot {
    return {
      state: this.phase,
      scanning: this.scanning,
      connected: this.connected,
      authenticated: false,
      devices: this.devices.map((row: VehicleBleDevice): VehicleBleDevice => ({
        id: row.id, name: row.name, paired: row.paired, connectable: row.connectable,
        rssi: row.rssi, lastSeenAt: row.lastSeenAt, yadeaCandidate: row.yadeaCandidate
      })),
      selectedDeviceId: this.selectedDeviceId,
      services: this.services.map((service: VehicleBleService): VehicleBleService => ({
        uuid: service.uuid, primary: service.primary,
        characteristics: service.characteristics.map((row: VehicleBleCharacteristic): VehicleBleCharacteristic => ({
          uuid: row.uuid, readable: row.readable, writable: row.writable, notifiable: row.notifiable
        }))
      })),
      standardInfo: {
        manufacturer: this.info.manufacturer, model: this.info.model, firmware: this.info.firmware,
        hardware: this.info.hardware, software: this.info.software, batteryLevel: this.info.batteryLevel,
        observedAt: this.info.observedAt
      },
      error: this.error,
      readErrorCount: this.readErrorCount
    };
  }

  /** 读取本机已配对设备，结果不证明这些设备使用 BLE 或属于雅迪。 */
  loadPaired(): void {
    if (this.disposed || !this.idleForUserAction()) return;
    try {
      this.ensureBluetooth();
      const ids = connection.getPairedDevices().slice(0, VehicleBlePolicy.MAX_DEVICES);
      // 已配对列表是本次系统快照，取消配对的旧记录不能继续冒充已配对设备。
      this.devices = [];
      ids.forEach((id: string): void => {
        if (!VehicleBlePolicy.deviceId(id)) return;
        let name = '';
        try { name = connection.getRemoteDeviceName(id); } catch (_) {}
        this.devices = VehicleBlePolicy.mergeDevice(this.devices, {
          id, name, paired: true, connectable: true, rssi: undefined, lastSeenAt: 0,
          yadeaCandidate: VehicleBlePolicy.yadeaCandidate(name, new Uint8Array(0))
        });
      });
      this.error = '';
      this.phase = 'IDLE';
      this.publish();
    } catch (error) { this.report(this.errorCode(error, 'VEHICLE_BLE_PAIR_LIST_FAILED')); }
  }

  /** 显式扫描附近设备十二秒，最多保留四十个去重结果。 */
  scan(): void {
    if (this.disposed || !this.idleForUserAction()) return;
    try {
      this.ensureBluetooth();
      const revision = this.epoch.begin();
      // 新一轮扫描仅保留配对记录，避免旧广播形成当前在场的错误印象。
      this.devices = this.devices.filter((row: VehicleBleDevice): boolean => row.paired).map((row: VehicleBleDevice): VehicleBleDevice => ({
        id: row.id, name: row.name, paired: true, connectable: true, rssi: undefined,
        lastSeenAt: 0, yadeaCandidate: row.yadeaCandidate
      }));
      const listener = (rows: ble.ScanResult[]): void => {
        if (!this.epoch.current(revision) || !this.scanning) return;
        rows.slice(0, VehicleBlePolicy.MAX_DEVICES).forEach((row: ble.ScanResult): void => {
          this.devices = VehicleBlePolicy.mergeDevice(this.devices, {
            id: row.deviceId, name: row.deviceName, paired: false, connectable: row.connectable,
            rssi: row.rssi, lastSeenAt: Date.now(),
            yadeaCandidate: VehicleBlePolicy.yadeaCandidate(row.deviceName, new Uint8Array(row.data))
          });
        });
        this.publish();
      };
      this.scanListener = listener;
      ble.on('BLEDeviceFind', listener);
      this.scanning = true;
      this.phase = 'SCANNING';
      this.error = '';
      // 系统以 null 表示无过滤；空数组会在原生参数校验中被拒绝。
      ble.startBLEScan(null, { interval: 0, dutyMode: ble.ScanDuty.SCAN_MODE_LOW_POWER,
        matchMode: ble.MatchMode.MATCH_MODE_AGGRESSIVE });
      this.scanTimer = setTimeout((): void => {
        if (!this.epoch.current(revision)) return;
        this.stopScan();
      }, VehicleBlePolicy.SCAN_DURATION_MS);
      this.publish();
    } catch (error) {
      this.epoch.invalidate();
      this.stopScanInternal();
      this.report(this.errorCode(error, 'VEHICLE_BLE_SCAN_FAILED'));
    }
  }

  /** 停止当前扫描，不启动重扫或自动连接。 */
  stopScan(): void {
    if (this.disposed || !this.scanning) return;
    this.epoch.invalidate();
    this.stopScanInternal();
    this.phase = 'IDLE';
    this.publish();
  }

  /** 仅连接用户从本轮发现或配对列表中选择的设备，并读取标准白名单。 */
  async connect(deviceId: string): Promise<void> {
    if (this.disposed) return;
    if (this.client !== undefined || this.phase === 'CONNECTING' || this.phase === 'DISCOVERING') {
      this.report('VEHICLE_BLE_BUSY', false);
      return;
    }
    const device = this.devices.find((row: VehicleBleDevice): boolean => row.id === deviceId);
    if (device === undefined || !VehicleBlePolicy.deviceId(deviceId) || !device.connectable) {
      this.report('VEHICLE_BLE_INVALID_DEVICE');
      return;
    }
    let revision = -1;
    try {
      this.ensureBluetooth();
      this.stopScanInternal();
      revision = this.epoch.begin();
      this.selectedDeviceId = deviceId;
      this.services = [];
      this.info = VehicleBlePolicy.emptyInfo();
      this.readErrorCount = 0;
      this.error = '';
      this.phase = 'CONNECTING';
      const client = ble.createGattClientDevice(deviceId);
      this.client = client;
      let ready: (() => void) | undefined = undefined;
      const listener = (event: ble.BLEConnectionChangeState): void => {
        if (!this.epoch.current(revision) || this.client !== client || event.deviceId.toLowerCase() !== deviceId.toLowerCase()) return;
        if (event.state === constant.ProfileConnectionState.STATE_CONNECTED) {
          if (this.connected) return;
          this.connected = true;
          this.phase = 'DISCOVERING';
          const resolve = ready;
          ready = undefined;
          resolve?.();
          this.publish();
        } else if (event.state === constant.ProfileConnectionState.STATE_DISCONNECTED) {
          this.failConnection('VEHICLE_BLE_DISCONNECTED', revision);
        }
      };
      this.connectionListener = listener;
      client.on('BLEConnectionStateChange', listener);
      this.publish();
      if (!this.epoch.current(revision)) return;
      const connected = new Promise<void>((resolve: () => void): void => {
        ready = resolve;
      });
      // 先建立等待与超时，再调用同步连接入口，避免极快回调丢失。
      const waiting = this.bounded(connected, revision, VehicleBlePolicy.CONNECT_TIMEOUT_MS, 'VEHICLE_BLE_CONNECT_TIMEOUT');
      try { client.connect(); }
      catch (error) {
        this.cancelPending();
        // 等待器已被取消，消费其拒绝结果，最终只报告实际连接错误。
        await waiting.catch((): void => {});
        throw new Error(this.errorCode(error, 'VEHICLE_BLE_CONNECT_FAILED'));
      }
      await waiting;
      if (!this.epoch.current(revision)) return;
      let discovered: ble.GattService[];
      try {
        discovered = await this.bounded(client.getServices(), revision,
          VehicleBlePolicy.DISCOVERY_TIMEOUT_MS, 'VEHICLE_BLE_DISCOVERY_TIMEOUT');
      } catch (error) { throw new Error(this.errorCode(error, 'VEHICLE_BLE_DISCOVERY_FAILED')); }
      if (!this.epoch.current(revision)) return;
      const reads = this.summarizeServices(discovered);
      this.publish();
      for (let index = 0; index < reads.length; index++) {
        if (!this.epoch.current(revision)) return;
        const read = reads[index];
        try {
          const characteristic: ble.BLECharacteristic = {
            serviceUuid: read.serviceUuid, characteristicUuid: read.characteristicUuid,
            characteristicValue: new ArrayBuffer(0), descriptors: []
          };
          const result = await this.bounded(client.readCharacteristicValue(characteristic), revision,
            VehicleBlePolicy.READ_TIMEOUT_MS, 'VEHICLE_BLE_READ_TIMEOUT');
          if (!this.epoch.current(revision)) return;
          if (VehicleBlePolicy.uuid(result.serviceUuid) !== read.serviceUuid ||
            VehicleBlePolicy.uuid(result.characteristicUuid) !== read.characteristicUuid ||
            !VehicleBlePolicy.applyStandardValue(this.info, read, new Uint8Array(result.characteristicValue), Date.now())) {
            this.readErrorCount++;
          }
        } catch (error) {
          if (!this.epoch.current(revision)) return;
          const code = this.errorCode(error, 'VEHICLE_BLE_READ_FAILED');
          // 超时的原生读取可能仍在途，断开后才允许下一次用户发起活动。
          if (code === 'VEHICLE_BLE_READ_TIMEOUT' || code === 'VEHICLE_BLE_PERMISSION' || code === 'VEHICLE_BLE_OFF') throw new Error(code);
          this.readErrorCount++;
        }
      }
      if (!this.epoch.current(revision)) return;
      this.phase = 'CONNECTED';
      this.error = this.readErrorCount > 0 ? 'VEHICLE_BLE_READ_FAILED' : '';
      this.publish();
    } catch (error) {
      if (revision >= 0 && !this.epoch.current(revision)) return;
      this.failConnection(this.errorCode(error, 'VEHICLE_BLE_CONNECT_FAILED'), revision);
    }
  }

  /** 断开本实例连接并使所有未完成读取失效，不改变系统配对和车辆绑定。 */
  disconnect(): void {
    if (this.disposed) return;
    this.epoch.invalidate();
    this.cancelPending();
    this.stopScanInternal();
    this.releaseClient();
    this.phase = 'IDLE';
    this.error = '';
    this.publish();
  }

  /** 页面离开、后台或切换账户时永久清理实例，不再发出任何界面回调。 */
  close(): void {
    if (this.disposed) return;
    this.disposed = true;
    this.epoch.close();
    this.cancelPending();
    this.stopScanInternal();
    this.releaseClient();
    if (this.accessSubscribed) {
      try { access.off('stateChange', this.accessListener); } catch (_) {}
      this.accessSubscribed = false;
    }
    this.devices = [];
    this.services = [];
    this.info = VehicleBlePolicy.emptyInfo();
    this.selectedDeviceId = '';
    this.error = '';
    this.phase = 'CLOSED';
  }

  private summarizeServices(discovered: ble.GattService[]): VehicleBleStandardRead[] {
    const reads: VehicleBleStandardRead[] = [];
    const summaries: VehicleBleService[] = [];
    let characteristicCount = 0;
    discovered.slice(0, VehicleBlePolicy.MAX_SERVICES).forEach((service: ble.GattService): void => {
      const uuid = VehicleBlePolicy.uuid(service.serviceUuid);
      if (uuid.length === 0) return;
      const characteristics: VehicleBleCharacteristic[] = [];
      service.characteristics.slice(0, VehicleBlePolicy.MAX_CHARACTERISTICS - characteristicCount)
        .forEach((row: ble.BLECharacteristic): void => {
          const characteristicUuid = VehicleBlePolicy.uuid(row.characteristicUuid);
          if (characteristicUuid.length === 0 || VehicleBlePolicy.uuid(row.serviceUuid) !== uuid) return;
          characteristicCount++;
          const readable = row.properties?.read === true;
          characteristics.push({ uuid: characteristicUuid, readable,
            writable: row.properties?.write === true || row.properties?.writeNoResponse === true,
            notifiable: row.properties?.notify === true || row.properties?.indicate === true });
          const read = VehicleBlePolicy.standardRead(uuid, characteristicUuid);
          if (readable && read !== undefined && !reads.some((item: VehicleBleStandardRead): boolean => item.field === read.field)) reads.push(read);
        });
      summaries.push({ uuid, primary: service.isPrimary, characteristics });
    });
    this.services = summaries;
    return reads;
  }

  private bounded<T>(task: Promise<T>, revision: number, duration: number, timeoutCode: string): Promise<T> {
    return new Promise<T>((resolve: (value: T) => void, reject: (error: Error) => void): void => {
      let finished = false;
      const complete = (value: T): void => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        if (this.pendingAbort === abort) this.pendingAbort = undefined;
        if (!this.epoch.current(revision)) reject(new Error('VEHICLE_BLE_CANCELLED'));
        else resolve(value);
      };
      const fail = (error: Error): void => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        if (this.pendingAbort === abort) this.pendingAbort = undefined;
        reject(error);
      };
      const abort = (): void => fail(new Error('VEHICLE_BLE_CANCELLED'));
      const timer = setTimeout((): void => fail(new Error(timeoutCode)), duration);
      this.pendingAbort = abort;
      task.then(complete).catch((error: Error): void => fail(error));
      if (!this.epoch.current(revision)) abort();
    });
  }

  private ensureBluetooth(): void {
    try {
      if (!this.bluetoothReady(access.getState())) throw new Error('VEHICLE_BLE_OFF');
      if (!this.accessSubscribed) {
        access.on('stateChange', this.accessListener);
        this.accessSubscribed = true;
      }
    } catch (error) {
      throw new Error(this.errorCode(error, 'VEHICLE_BLE_UNSUPPORTED'));
    }
  }

  private bluetoothReady(state: access.BluetoothState): boolean {
    return state === access.BluetoothState.STATE_ON || state === access.BluetoothState.STATE_BLE_ON;
  }

  private idleForUserAction(): boolean {
    if (this.scanning || this.client !== undefined || this.phase === 'CONNECTING' || this.phase === 'DISCOVERING') {
      this.report('VEHICLE_BLE_BUSY', false);
      return false;
    }
    return true;
  }

  private stopScanInternal(): void {
    if (this.scanTimer >= 0) clearTimeout(this.scanTimer);
    this.scanTimer = -1;
    const listener = this.scanListener;
    this.scanListener = undefined;
    if (listener !== undefined) {
      try { ble.off('BLEDeviceFind', listener); } catch (_) {}
    }
    const wasScanning = this.scanning;
    this.scanning = false;
    if (wasScanning) {
      try { ble.stopBLEScan(); } catch (_) {}
    }
  }

  private cancelPending(): void {
    const abort = this.pendingAbort;
    this.pendingAbort = undefined;
    abort?.();
  }

  private releaseClient(): void {
    const client = this.client;
    const listener = this.connectionListener;
    this.client = undefined;
    this.connectionListener = undefined;
    this.connected = false;
    this.selectedDeviceId = '';
    this.services = [];
    this.info = VehicleBlePolicy.emptyInfo();
    this.readErrorCount = 0;
    if (client === undefined) return;
    if (listener !== undefined) {
      try { client.off('BLEConnectionStateChange', listener); } catch (_) {}
    }
    try { client.disconnect(); } catch (_) {}
    try { client.close(); } catch (_) {}
  }

  private failConnection(code: string, revision: number): void {
    if (this.disposed || (revision >= 0 && !this.epoch.current(revision))) return;
    this.epoch.invalidate();
    this.cancelPending();
    this.stopScanInternal();
    this.releaseClient();
    this.report(code);
  }

  private errorCode(error: Object, fallback: string): string {
    const value = error as Error;
    if (/^VEHICLE_BLE_[A-Z_]+$/.test(value.message ?? '')) return value.message;
    const failure = error as BusinessFailure;
    if (failure.code === 201) return 'VEHICLE_BLE_PERMISSION';
    if (failure.code === 801) return 'VEHICLE_BLE_UNSUPPORTED';
    if (failure.code === 2900003 || failure.code === 2900001) return 'VEHICLE_BLE_OFF';
    return fallback;
  }

  private report(code: string, changeState: boolean = true): void {
    if (this.disposed) return;
    this.error = code;
    if (changeState) this.phase = 'ERROR';
    this.publish();
  }

  private publish(): void {
    if (this.disposed) return;
    try { this.observer(this.snapshot()); } catch (_) {}
  }
}

/** 只检查系统错误编号，不传播可能含设备地址的原始消息。 */
interface BusinessFailure { code: number; }
