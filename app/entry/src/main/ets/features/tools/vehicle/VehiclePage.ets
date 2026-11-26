import { abilityAccessCtrl, common } from '@kit.AbilityKit';
import { AppTheme } from '../../../theme/AppTheme';
import { AppButton, AppTextAction, AppIconButton } from '../../../components/AppControls';
import { ToolEmptyState } from '../ToolUi';
import { VehicleBleDevice, VehicleBleService, VehicleBleSnapshot } from './VehicleBleModels';
import { VehicleBlePolicy } from './VehicleBlePolicy';
import { VehicleBleTransport } from './VehicleBleTransport';
import { VehicleShortcutPolicy } from './VehicleShortcutPolicy';

/** 雅迪近场接入页，只进行用户主动发起的发现、连接和标准只读验证。 */
@Component
export struct VehiclePage {
  @Prop @Watch('scopeChanged') baseUrl: string = '';
  @Prop @Watch('scopeChanged') accountKey: string = '';
  @Prop @Watch('visibilityChanged') active: boolean = true;
  @Prop @Watch('shortcutChanged') shortcutRequest: string = '';
  @StorageProp('appForeground') @Watch('foregroundChanged') private foreground: boolean = true;
  onBack: () => void = () => {};
  @State private view: VehicleBleSnapshot = {
    state: 'IDLE', scanning: false, connected: false, authenticated: false, devices: [],
    selectedDeviceId: '', services: [], standardInfo: VehicleBlePolicy.emptyInfo(), error: '', readErrorCount: 0
  };
  @State private permissionPending: boolean = false;
  @State private notice: string = '';
  @State private serviceDetails: boolean = false;
  @State private connectionOpen: boolean = false;
  @State private detailsVisible: boolean = false;
  @State private actionTitle: string = '';
  @State private actionMessage: string = '';
  @State private unlockFocused: boolean = false;
  private lastShortcutRequest: string = '';
  private transport: VehicleBleTransport | undefined = undefined;
  private mounted: boolean = false;
  private revision: number = 0;
  private readonly scroller: Scroller = new Scroller();

  /** 进入页面只恢复界面，不自动扫描、申请权限或连接设备。 */
  aboutToAppear(): void { this.mounted = true; this.shortcutChanged(); }

  /** 页面销毁后释放所有蓝牙句柄并使权限弹窗的迟到结果失效。 */
  aboutToDisappear(): void { this.mounted = false; this.release(false); }

  private label(value: Resource): string {
    try { return this.getUIContext().getHostContext()?.resourceManager.getStringSync(value.id) ?? ''; }
    catch (_) { return ''; }
  }

  private usable(): boolean { return this.mounted && this.active && this.foreground && this.accountKey.length > 0; }

  private busy(): boolean {
    return this.permissionPending || this.view.state === 'CONNECTING' || this.view.state === 'DISCOVERING';
  }

  private emptyView(): VehicleBleSnapshot {
    return { state: 'IDLE', scanning: false, connected: false, authenticated: false, devices: [],
      selectedDeviceId: '', services: [], standardInfo: VehicleBlePolicy.emptyInfo(), error: '', readErrorCount: 0 };
  }

  private release(showNotice: boolean): void {
    // 先失效页面回调，再关闭传输器，防止关闭过程中的事件重新挂回旧设备。
    this.revision++;
    const previous = this.transport;
    this.transport = undefined;
    previous?.close();
    this.view = this.emptyView();
    this.permissionPending = false;
    this.serviceDetails = false;
    this.connectionOpen = false;
    this.detailsVisible = false;
    this.actionTitle = '';
    this.actionMessage = '';
    this.unlockFocused = false;
    this.notice = showNotice ? this.label($r('app.string.vehicle_paused')) : '';
  }

  private scopeChanged(): void { this.release(false); }

  private visibilityChanged(): void {
    if (!this.active) this.release(true);
    else this.shortcutChanged();
  }

  private foregroundChanged(): void {
    if (!this.foreground) this.release(true);
    else this.shortcutChanged();
  }

  private client(): VehicleBleTransport {
    if (this.transport === undefined) {
      const revision = this.revision;
      this.transport = new VehicleBleTransport((snapshot: VehicleBleSnapshot): void => {
        // 当前账户、页面和前台状态共同限制异步结果的使用范围。
        if (this.usable() && revision === this.revision) this.view = snapshot;
      });
      this.view = this.transport.snapshot();
    }
    return this.transport;
  }

  private async authorize(): Promise<boolean> {
    if (!this.usable() || this.permissionPending) return false;
    const revision = this.revision;
    this.permissionPending = true;
    this.notice = '';
    try {
      const result = await abilityAccessCtrl.createAtManager().requestPermissionsFromUser(
        this.getUIContext().getHostContext() as common.UIAbilityContext, ['ohos.permission.ACCESS_BLUETOOTH']);
      if (!this.usable() || revision !== this.revision) return false;
      const granted = result.authResults.length === 1 && result.authResults[0] === 0;
      if (!granted) this.notice = this.label($r('app.string.vehicle_permission_denied'));
      return granted;
    } catch (_) {
      if (this.usable() && revision === this.revision) this.notice = this.label($r('app.string.vehicle_permission_failed'));
      return false;
    } finally {
      if (revision === this.revision) this.permissionPending = false;
    }
  }

  private async scan(): Promise<void> {
    if (this.busy() || this.view.connected) return;
    this.connectionOpen = true;
    const revision = this.revision;
    // 授权完成和调用方恢复之间仍可能切页，调用蓝牙前再次核验作用域。
    if (await this.authorize() && this.usable() && revision === this.revision) this.client().scan();
  }

  private async paired(): Promise<void> {
    if (this.busy() || this.view.connected || this.view.scanning) return;
    this.connectionOpen = true;
    const revision = this.revision;
    if (await this.authorize() && this.usable() && revision === this.revision) this.client().loadPaired();
  }

  private async connect(device: VehicleBleDevice): Promise<void> {
    if (this.busy() || this.view.connected || !device.connectable) return;
    this.connectionOpen = true;
    const revision = this.revision;
    if (await this.authorize() && this.usable() && revision === this.revision) {
      this.serviceDetails = false;
      await this.client().connect(device.id);
    }
  }

  private disconnect(): void {
    if (!this.usable()) return;
    this.notice = '';
    this.transport?.disconnect();
    this.serviceDetails = false;
  }

  private stopScan(): void {
    if (this.usable()) this.transport?.stopScan();
  }

  private back(): void { this.release(false); this.onBack(); }

  private statusLabel(): Resource {
    if (this.view.state === 'SCANNING') return $r('app.string.vehicle_state_scanning');
    if (this.view.state === 'CONNECTING') return $r('app.string.vehicle_state_connecting');
    if (this.view.state === 'DISCOVERING') return $r('app.string.vehicle_state_discovering');
    if (this.view.connected) return $r('app.string.vehicle_state_connected');
    if (this.view.error === 'VEHICLE_BLE_DISCONNECTED') return $r('app.string.vehicle_state_disconnected');
    if (this.view.state === 'ERROR') return $r('app.string.vehicle_state_error');
    return $r('app.string.vehicle_state_idle');
  }

  private errorLabel(): Resource {
    const code = this.view.error;
    if (code === 'VEHICLE_BLE_PERMISSION') return $r('app.string.vehicle_permission_denied');
    if (code === 'VEHICLE_BLE_OFF') return $r('app.string.vehicle_bluetooth_off');
    if (code === 'VEHICLE_BLE_UNSUPPORTED') return $r('app.string.vehicle_error_unavailable');
    if (code === 'VEHICLE_BLE_BUSY') return $r('app.string.vehicle_error_busy');
    if (code === 'VEHICLE_BLE_SCAN_FAILED') return $r('app.string.vehicle_error_scan');
    if (code === 'VEHICLE_BLE_PAIR_LIST_FAILED') return $r('app.string.vehicle_error_paired');
    if (code === 'VEHICLE_BLE_INVALID_DEVICE') return $r('app.string.vehicle_error_device');
    if (code === 'VEHICLE_BLE_CONNECT_FAILED' || code === 'VEHICLE_BLE_DISCONNECTED') return $r('app.string.vehicle_error_connect');
    if (code === 'VEHICLE_BLE_CONNECT_TIMEOUT' || code === 'VEHICLE_BLE_DISCOVERY_TIMEOUT') return $r('app.string.vehicle_error_timeout');
    if (code === 'VEHICLE_BLE_DISCOVERY_FAILED') return $r('app.string.vehicle_error_discover');
    if (code === 'VEHICLE_BLE_READ_FAILED' || code === 'VEHICLE_BLE_READ_TIMEOUT') return $r('app.string.vehicle_error_read');
    return $r('app.string.vehicle_error_unknown');
  }

  private selectedName(): ResourceStr {
    const selected = this.view.devices.find((row: VehicleBleDevice): boolean => row.id === this.view.selectedDeviceId);
    return selected !== undefined && selected.name.length > 0 ? selected.name : $r('app.string.vehicle_device_unnamed');
  }

  private standardInfoAvailable(): boolean {
    const info = this.view.standardInfo;
    return info.manufacturer.length > 0 || info.model.length > 0 || info.firmware.length > 0 ||
      info.hardware.length > 0 || info.software.length > 0 || info.batteryLevel !== undefined;
  }

  /** 桌面请求只表达打开解锁入口，不代表用户或车辆已经授权。 */
  private shortcutChanged(): void {
    if (!this.mounted || !this.active || !this.foreground ||
      !VehicleShortcutPolicy.isCurrent(this.shortcutRequest, Date.now()) ||
      this.shortcutRequest === this.lastShortcutRequest) return;
    this.lastShortcutRequest = this.shortcutRequest;
    this.showAction('UNLOCK');
    this.scrollHome();
  }

  private scrollHome(): void {
    try { this.scroller.scrollTo({ xOffset: 0, yOffset: 0, animation: { duration: 180 } }); } catch (_) {}
  }

  private showAction(kind: string): void {
    if (!this.mounted || !this.active || !this.foreground) return;
    this.unlockFocused = kind === 'UNLOCK' || kind === 'LOCK';
    this.actionTitle = this.label(kind === 'POWER' ? $r('app.string.vehicle_home_power') :
      kind === 'FIND' ? $r('app.string.vehicle_home_find') : kind === 'LOCATION' ?
      $r('app.string.vehicle_location_title') : kind === 'LOCK' ? $r('app.string.vehicle_home_lock') :
      $r('app.string.vehicle_home_unlock_title'));
    // 入口不发出任何控制字节，未完成鉴权时只解释当前可用范围。
    if (this.accountKey.length === 0) this.actionMessage = this.label($r('app.string.vehicle_logged_out'));
    else if (kind === 'POWER') this.actionMessage = this.label($r('app.string.vehicle_home_power_unavailable'));
    else if (kind === 'FIND') this.actionMessage = this.label($r('app.string.vehicle_home_find_unavailable'));
    else if (kind === 'LOCATION') this.actionMessage = this.label($r('app.string.vehicle_location_pending'));
    else this.actionMessage = this.label(kind === 'UNLOCK' ? $r('app.string.vehicle_home_unlock_hint') :
      $r('app.string.vehicle_home_lock_unavailable'));
  }

  private openDetails(): void {
    this.detailsVisible = !this.detailsVisible;
    if (!this.detailsVisible) return;
    const revision = this.revision;
    // 仅在当前页面的下一次布局后定位详情，不允许离页回调滚动新页面。
    setTimeout((): void => {
      if (!this.usable() || revision !== this.revision || !this.detailsVisible) return;
      try { this.scroller.scrollTo({ xOffset: 0, yOffset: 10000, animation: { duration: 180 } }); } catch (_) {}
    }, 40);
  }

  private toggleConnection(): void {
    this.connectionOpen = !this.connectionOpen;
  }

  private openConnection(): void {
    if (!this.mounted || !this.active || !this.foreground) return;
    this.connectionOpen = true;
    const revision = this.revision;
    // 展开后越过车辆大图定位连接区域，不申请权限或启动蓝牙活动。
    setTimeout((): void => {
      if (!this.usable() || revision !== this.revision || !this.connectionOpen) return;
      try { this.scroller.scrollTo({ xOffset: 0, yOffset: 450, animation: { duration: 180 } }); } catch (_) {}
    }, 40);
  }

  private homeName(): ResourceStr {
    if (this.view.connected && this.view.selectedDeviceId.length > 0) return this.selectedName();
    return $r('app.string.vehicle_home_car_name');
  }

  @Builder
  private SectionTitle(title: Resource, icon: Resource) {
    Row({ space: 8 }) {
      SymbolGlyph(icon).fontSize(18).fontColor([AppTheme.primary])
      Text(title).fontFamily(AppTheme.fontSemibold).fontSize(17).lineHeight(25)
        .fontWeight(600).fontColor(AppTheme.textPrimary).layoutWeight(1)
    }.width('100%')
  }

  @Builder
  private InformationRow(title: Resource, value: ResourceStr) {
    Row({ space: 14 }) {
      Text(title).fontFamily(AppTheme.fontFamily).fontSize(14).lineHeight(22)
        .fontColor(AppTheme.textSecondary).constraintSize({ minWidth: 84, maxWidth: '42%' })
      Text(value).fontFamily(AppTheme.fontMedium).fontSize(14).lineHeight(22)
        .fontColor(AppTheme.textPrimary).layoutWeight(1).textAlign(TextAlign.End)
    }.width('100%').alignItems(VerticalAlign.Top)
  }

  @Builder
  private ConnectionCard() {
    Column({ space: 12 }) {
      this.SectionTitle($r('app.string.vehicle_connection_title'), $r('sys.symbol.bluetooth'))
      Row({ space: 10 }) {
        if (this.busy() || this.view.scanning) {
          LoadingProgress().width(18).height(18).color(AppTheme.primary)
            .accessibilityText(this.statusLabel())
        } else {
          Circle().width(8).height(8).fill(this.view.connected ? AppTheme.success : AppTheme.textTertiary)
        }
        Text(this.statusLabel()).fontFamily(AppTheme.fontMedium).fontSize(16).lineHeight(24)
          .fontColor(AppTheme.textPrimary).layoutWeight(1)
        Text($r('app.string.vehicle_read_only')).fontFamily(AppTheme.fontMedium).fontSize(12).lineHeight(18)
          .fontColor(AppTheme.primary).padding({ left: 8, right: 8, top: 4, bottom: 4 })
          .borderRadius(AppTheme.pillRadius).backgroundColor(AppTheme.accentSoft)
      }.width('100%')
      if (this.view.selectedDeviceId.length > 0) {
        Text(this.selectedName()).width('100%').fontFamily(AppTheme.fontSemibold).fontSize(18).lineHeight(26)
          .fontColor(AppTheme.textPrimary).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        this.InformationRow($r('app.string.vehicle_device_identity'), VehicleBlePolicy.maskedId(this.view.selectedDeviceId))
      }
      if (this.view.connected || this.view.state === 'CONNECTING' || this.view.state === 'DISCOVERING') {
        AppButton({ label: $r('app.string.vehicle_disconnect'), variant: 'secondary', fullWidth: true,
          available: this.usable(), action: (): void => this.disconnect() }).width('100%')
      } else {
        AppButton({ label: this.view.scanning ? $r('app.string.vehicle_stop_scan') : $r('app.string.vehicle_scan'),
          fullWidth: true, available: this.usable() && !this.busy(),
          action: (): void => { if (this.view.scanning) this.stopScan(); else this.scan(); } }).width('100%')
        AppTextAction({ label: $r('app.string.vehicle_paired'), available: this.usable() && !this.busy() && !this.view.scanning,
          action: (): void => { this.paired(); } }).width('100%')
        Text($r('app.string.vehicle_scan_hint')).width('100%').fontFamily(AppTheme.fontFamily).fontSize(13).lineHeight(21)
          .fontColor(AppTheme.textSecondary)
      }
    }.width('100%').padding(18).alignItems(HorizontalAlign.Start)
      .backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
  }

  @Builder
  private DeviceCard(device: VehicleBleDevice) {
    Button({ type: ButtonType.Normal }) {
      Row({ space: 12 }) {
        Image($rawfile('tools/yadea-vehicle.svg')).width(46).height(46)
          .padding(9).backgroundColor(AppTheme.accentSoft).borderRadius(12)
        Column({ space: 4 }) {
          Text(device.name.length > 0 ? device.name : $r('app.string.vehicle_device_unnamed'))
            .width('100%').fontFamily(AppTheme.fontSemibold).fontSize(15).lineHeight(23)
            .fontColor(AppTheme.textPrimary).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(VehicleBlePolicy.maskedId(device.id)).fontFamily(AppTheme.fontFamily).fontSize(12).lineHeight(18)
            .fontColor(AppTheme.textSecondary).width('100%')
          Text(device.yadeaCandidate ? $r('app.string.vehicle_yadea_candidate') :
            device.paired ? $r('app.string.vehicle_device_paired') : $r('app.string.vehicle_unverified_device'))
            .fontFamily(AppTheme.fontFamily).fontSize(12).lineHeight(18).fontColor(AppTheme.textSecondary).width('100%')
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        if (this.view.selectedDeviceId === device.id && this.view.connected) {
          SymbolGlyph($r('sys.symbol.checkmark_circle')).fontSize(22).fontColor([AppTheme.success])
        } else if (device.connectable && !this.view.connected) {
          Text($r('app.string.vehicle_connect')).fontFamily(AppTheme.fontMedium).fontSize(14).lineHeight(22)
            .fontColor(this.busy() ? AppTheme.disabledText : AppTheme.primary)
        }
      }.width('100%').alignItems(VerticalAlign.Center)
    }.width('100%').padding(14).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
      .constraintSize({ minHeight: 88 }).enabled(this.usable() && !this.busy() && !this.view.connected && device.connectable)
      .onClick((): void => { this.connect(device); })
  }

  @Builder
  private DeviceList() {
    if (this.view.devices.length > 0) {
      Column({ space: 10 }) {
        this.SectionTitle($r('app.string.vehicle_devices'), $r('sys.symbol.magnifyingglass'))
        ForEach(this.view.devices, (device: VehicleBleDevice): void => {
          this.DeviceCard(device)
        }, (device: VehicleBleDevice): string => device.id)
      }.width('100%')
    } else if (!this.view.scanning && !this.busy()) {
      ToolEmptyState({ icon: $r('sys.symbol.magnifyingglass'), title: $r('app.string.vehicle_no_devices'),
        description: $r('app.string.vehicle_no_devices_hint') })
    }
  }

  @Builder
  private StandardInformation() {
    if (this.view.connected) {
      Column({ space: 12 }) {
        this.SectionTitle($r('app.string.vehicle_details'), $r('sys.symbol.info_circle'))
        if (this.view.standardInfo.manufacturer.length > 0) this.InformationRow($r('app.string.vehicle_manufacturer'), this.view.standardInfo.manufacturer)
        if (this.view.standardInfo.model.length > 0) this.InformationRow($r('app.string.vehicle_model'), this.view.standardInfo.model)
        if (this.view.standardInfo.firmware.length > 0) this.InformationRow($r('app.string.vehicle_firmware'), this.view.standardInfo.firmware)
        if (this.view.standardInfo.hardware.length > 0) this.InformationRow($r('app.string.vehicle_hardware'), this.view.standardInfo.hardware)
        if (this.view.standardInfo.software.length > 0) this.InformationRow($r('app.string.vehicle_software'), this.view.standardInfo.software)
        if (this.view.standardInfo.batteryLevel !== undefined) {
          this.InformationRow($r('app.string.vehicle_battery'), `${this.view.standardInfo.batteryLevel}%`)
        }
        if (!this.standardInfoAvailable()) {
          Text($r('app.string.vehicle_no_standard_info')).width('100%').fontFamily(AppTheme.fontFamily).fontSize(14).lineHeight(22)
            .fontColor(AppTheme.textSecondary)
        } else if (this.view.standardInfo.observedAt > 0) {
          this.InformationRow($r('app.string.vehicle_last_read'), new Date(this.view.standardInfo.observedAt).toLocaleTimeString())
        }
        if (this.view.readErrorCount > 0) {
          Text($r('app.string.vehicle_error_read')).width('100%').fontFamily(AppTheme.fontFamily).fontSize(13).lineHeight(21)
            .fontColor(AppTheme.warning)
        }
      }.width('100%').padding(18).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
    }
  }

  @Builder
  private ServiceRow(service: VehicleBleService) {
    Column({ space: 4 }) {
      Text(service.uuid).fontFamily(AppTheme.fontFamily).fontSize(12).lineHeight(19)
        .fontColor(AppTheme.textSecondary).width('100%').maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      Text(`${service.characteristics.length} ${this.label($r('app.string.vehicle_characteristic_count'))}`)
        .fontFamily(AppTheme.fontFamily).fontSize(12).lineHeight(19).fontColor(AppTheme.textTertiary).width('100%')
    }.width('100%').padding({ top: 8, bottom: 8 })
  }

  @Builder
  private Services() {
    if (this.view.services.length > 0) {
      Column({ space: 6 }) {
        AppTextAction({ label: `${this.label($r('app.string.vehicle_services'))} · ${this.view.services.length}`,
          action: (): void => { this.serviceDetails = !this.serviceDetails; } }).width('100%')
        if (this.serviceDetails) {
          ForEach(this.view.services, (service: VehicleBleService): void => {
            this.ServiceRow(service)
          }, (service: VehicleBleService): string => service.uuid)
        }
      }.width('100%').padding(14).backgroundColor(AppTheme.surface).borderRadius(AppTheme.cardRadius)
    }
  }

  @Builder
  private HomeHeader() {
    Row({ space: 8 }) {
      AppIconButton({ icon: $r('sys.symbol.chevron_left'), description: this.label($r('app.string.vehicle_home_back')),
        action: (): void => this.back() })
      Text(this.homeName()).fontFamily(AppTheme.fontSemibold).fontSize(20).lineHeight(28)
        .fontWeight(600).fontColor('#1D2633').layoutWeight(1).maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      AppIconButton({ icon: $r('sys.symbol.bluetooth'), description: this.label($r('app.string.vehicle_home_connection_status')),
        action: (): void => this.openConnection() })
    }.width('100%').height(56).padding({ left: 12, right: 16 })
      .backgroundColor('#AABEEA')
  }

  @Builder
  private Hero() {
    Stack({ alignContent: Alignment.BottomEnd }) {
      Image($rawfile('tools/vehicle/yadea-q9-hero.png')).width('100%').height(280)
        .objectFit(ImageFit.Cover).accessibilityText($r('app.string.vehicle_home_illustration'))
      Text($r('app.string.vehicle_home_illustration')).fontFamily(AppTheme.fontFamily).fontSize(10).lineHeight(16)
        .fontColor('#697386').padding({ left: 7, right: 7, top: 2, bottom: 2 })
        .borderRadius(8).backgroundColor('#CCFFFFFF').margin({ right: 18, bottom: 8 })
    }.width('100%').height(280)
  }

  @Builder
  private MainAction(title: Resource, icon: Resource, kind: string) {
    Button({ type: ButtonType.Normal }) {
      Column({ space: 9 }) {
        Stack({ alignContent: Alignment.Center }) {
          Circle().width(58).height(58).fill(Color.White)
            .stroke(kind === 'LOCK' && this.unlockFocused ? AppTheme.primary : Color.White)
            .strokeWidth(kind === 'LOCK' && this.unlockFocused ? 2 : 0)
          SymbolGlyph(icon).fontSize(28).fontColor([kind === 'LOCK' && this.unlockFocused ? AppTheme.primary : '#1B2534'])
        }.width(60).height(60)
        Text(title).fontFamily(AppTheme.fontMedium).fontSize(13).lineHeight(20).fontColor('#485468')
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      }.width('100%')
    }.layoutWeight(1).height(96).backgroundColor(Color.Transparent).borderRadius(16)
      .enabled(this.mounted && this.active && this.foreground)
      .accessibilityText(title)
      .onClick((): void => { if (kind === 'DETAILS') this.openDetails(); else this.showAction(kind); })
  }

  @Builder
  private MainActions() {
    Row({ space: 2 }) {
      this.MainAction($r('app.string.vehicle_home_power'), $r('sys.symbol.power'), 'POWER')
      this.MainAction($r('app.string.vehicle_home_lock'), $r('sys.symbol.lock'), 'LOCK')
      this.MainAction($r('app.string.vehicle_home_find'), $r('sys.symbol.speaker_wave_2'), 'FIND')
      this.MainAction($r('app.string.vehicle_home_details'), $r('sys.symbol.doc_plaintext'), 'DETAILS')
    }.width('100%').padding({ left: 14, right: 14, top: 5, bottom: 8 })
      .linearGradient({ direction: GradientDirection.Bottom, colors: [['#D6DBEE', 0], ['#F3F5F9', 1]] })
  }

  @Builder
  private ActionNotice() {
    if (this.actionMessage.length > 0) {
      Column({ space: 10 }) {
        Text(this.actionTitle).fontFamily(AppTheme.fontSemibold).fontSize(16).lineHeight(24)
          .fontColor(AppTheme.textPrimary).width('100%')
        Text(this.actionMessage).fontFamily(AppTheme.fontFamily).fontSize(14).lineHeight(22)
          .fontColor(AppTheme.textSecondary).width('100%')
        AppTextAction({ label: $r('app.string.vehicle_home_close'), action: (): void => {
          this.actionMessage = ''; this.actionTitle = ''; this.unlockFocused = false;
        } }).width('100%')
      }.width('100%').padding(16).backgroundColor(Color.White).borderRadius(20)
        .border({ width: 1, color: AppTheme.outline })
    }
  }

  @Builder
  private LocationCard() {
    Button({ type: ButtonType.Normal }) {
      Row({ space: 14 }) {
        Column({ space: 7 }) {
          Text($r('app.string.vehicle_location_title')).fontFamily(AppTheme.fontSemibold).fontSize(18).lineHeight(26)
            .fontColor('#1D2633').width('100%')
          Text($r('app.string.vehicle_home_location_unknown')).fontFamily(AppTheme.fontMedium).fontSize(14).lineHeight(22)
            .fontColor('#737D8D').width('100%')
          Text($r('app.string.vehicle_home_location_hint')).fontFamily(AppTheme.fontFamily).fontSize(12).lineHeight(19)
            .fontColor('#89919E').width('100%')
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        Stack({ alignContent: Alignment.Center }) {
          Circle().width(54).height(54).fill('#EEF2FA')
          SymbolGlyph($r('sys.symbol.location_north_up_right')).fontSize(25).fontColor(['#7B8EAF'])
        }.width(56).height(56)
      }.width('100%')
    }.width('100%').padding(18).constraintSize({ minHeight: 122 }).backgroundColor(Color.White).borderRadius(20)
      .accessibilityText($r('app.string.vehicle_home_location_action'))
      .onClick((): void => this.showAction('LOCATION'))
  }

  @Builder
  private ConnectionSection() {
    Column({ space: 12 }) {
      Button({ type: ButtonType.Normal }) {
        Row({ space: 12 }) {
          Column({ space: 5 }) {
            Text($r('app.string.vehicle_home_connection')).fontFamily(AppTheme.fontSemibold).fontSize(18).lineHeight(26)
              .fontColor('#1D2633').width('100%')
            Text(this.view.connected || this.view.scanning || this.busy() ? this.statusLabel() :
              $r('app.string.vehicle_home_connection_hint')).fontFamily(AppTheme.fontFamily).fontSize(13).lineHeight(21)
              .fontColor('#7B8494').width('100%')
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          SymbolGlyph(this.connectionOpen ? $r('sys.symbol.chevron_down') : $r('sys.symbol.chevron_right'))
            .fontSize(17).fontColor(['#8A94A4'])
        }.width('100%')
      }.width('100%').constraintSize({ minHeight: 56 }).backgroundColor(Color.Transparent)
        .accessibilityText(this.connectionOpen ? $r('app.string.vehicle_home_connection_close') :
          $r('app.string.vehicle_home_connection_open'))
        .onClick((): void => this.toggleConnection())
      if (this.connectionOpen) {
        Divider().color('#E9EDF3')
        Text($r('app.string.vehicle_intro')).fontFamily(AppTheme.fontFamily).fontSize(13).lineHeight(21)
          .fontColor(AppTheme.textSecondary).width('100%')
        this.ConnectionCard()
        if (this.notice.length > 0 || this.view.error.length > 0) {
          Text(this.notice.length > 0 ? this.notice : this.errorLabel()).width('100%')
            .fontFamily(AppTheme.fontFamily).fontSize(14).lineHeight(23).fontColor(AppTheme.danger)
            .padding(14).backgroundColor(AppTheme.dangerSoft).borderRadius(12)
        }
        this.DeviceList()
        this.Services()
      }
    }.width('100%').padding(18).alignItems(HorizontalAlign.Start).backgroundColor(Color.White).borderRadius(20)
  }

  @Builder
  private DetailsPanel() {
    if (this.detailsVisible) {
      if (this.view.connected) {
        this.StandardInformation()
      } else {
        Column({ space: 10 }) {
          this.SectionTitle($r('app.string.vehicle_home_details'), $r('sys.symbol.doc_plaintext'))
          Text($r('app.string.vehicle_home_details_empty')).fontFamily(AppTheme.fontFamily).fontSize(14).lineHeight(22)
            .fontColor(AppTheme.textSecondary).width('100%')
        }.width('100%').padding(18).backgroundColor(Color.White).borderRadius(20)
      }
    }
  }

  @Builder
  private Capabilities() {
    Row({ space: 8 }) {
      SymbolGlyph($r('sys.symbol.lock')).fontSize(14).fontColor(['#8B95A5'])
      Text($r('app.string.vehicle_auth_pending')).fontFamily(AppTheme.fontFamily).fontSize(12).lineHeight(20)
        .fontColor('#7B8494')
    }.width('100%').padding({ left: 8, top: 2, bottom: 4 })
  }

  /** 构建爱车主视图，控制入口与真实连接能力分别呈现。 */
  build() {
    Column() {
      this.HomeHeader()
      Scroll(this.scroller) {
        Column({ space: 0 }) {
          this.Hero()
          this.MainActions()
          Column({ space: 12 }) {
            if (this.accountKey.length === 0) {
              Text($r('app.string.vehicle_logged_out')).fontFamily(AppTheme.fontMedium).fontSize(14).lineHeight(22)
                .fontColor(AppTheme.warning).width('100%')
            }
            this.ActionNotice()
            this.LocationCard()
            this.ConnectionSection()
            this.DetailsPanel()
            this.Capabilities()
          }.width('100%').padding({ left: 16, right: 16, top: 4, bottom: 24 })
            .alignItems(HorizontalAlign.Start)
        }.width('100%')
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
    }.width('100%').height('100%').backgroundColor('#F3F5F9')
  }
}
