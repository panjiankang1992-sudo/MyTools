import { VehiclePage } from '../features/tools/vehicle/VehiclePage';

/** 仅在临时验收包使用生产车辆页面，不连接业务服务器或伪造蓝牙结果。 */
@Entry
@Component
struct VehicleAcceptance {
  @State private active: boolean = true;

  /** 系统返回时销毁页面，验证扫描与连接的生命周期清理。 */
  onBackPress(): boolean {
    this.active = false;
    return true;
  }

  /** 构建独立验收入口；正式页面清单不包含本入口。 */
  build() {
    Column() {
      if (this.active) {
        VehiclePage({ baseUrl: 'https://vehicle-acceptance.invalid', accountKey: 'vehicle-acceptance-only',
          active: this.active, onBack: (): void => { this.active = false; } })
      }
    }.width('100%').height('100%')
  }
}
