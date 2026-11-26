/** 蓝牙发现结果仅用于用户选择，不证明车辆身份。 */
export interface VehicleBleDevice {
  id: string;
  name: string;
  paired: boolean;
  connectable: boolean;
  rssi: number | undefined;
  lastSeenAt: number;
  yadeaCandidate: boolean;
}

/** 已发现特征的能力摘要，不提供任意读写入口。 */
export interface VehicleBleCharacteristic {
  uuid: string;
  readable: boolean;
  writable: boolean;
  notifiable: boolean;
}

/** 已发现服务的有界能力摘要。 */
export interface VehicleBleService {
  uuid: string;
  primary: boolean;
  characteristics: VehicleBleCharacteristic[];
}

/** 标准服务信息不等同于雅迪私有状态或控车鉴权结果。 */
export interface VehicleBleStandardInfo {
  manufacturer: string;
  model: string;
  firmware: string;
  hardware: string;
  software: string;
  batteryLevel: number | undefined;
  observedAt: number;
}

/** 每次通知均复制数据，避免界面修改传输层内部状态。 */
export interface VehicleBleSnapshot {
  state: string;
  scanning: boolean;
  connected: boolean;
  authenticated: boolean;
  devices: VehicleBleDevice[];
  selectedDeviceId: string;
  services: VehicleBleService[];
  standardInfo: VehicleBleStandardInfo;
  error: string;
  readErrorCount: number;
}

/** 标准只读请求的服务与特征，不包含供应商控制特征。 */
export interface VehicleBleStandardRead {
  serviceUuid: string;
  characteristicUuid: string;
  field: string;
}
