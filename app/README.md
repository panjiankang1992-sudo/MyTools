# MyTools HarmonyOS App

本目录承载 MyTools 的 HarmonyOS NEXT 客户端。当前已经建立 API 12、Stage 模型的可编译工程基线，并持续按设计规格实现功能。

## 产品范围

应用连接 MyTools Spring Boot 后端，并提供登录、多媒体、电子书、Copilot、工具和个人中心能力。登录是进入应用的前置流程；登录后的一级导航固定为五个主页面：

1. 电子书
2. 工具
3. Copilot
4. 多媒体
5. 我的

完整方案见 [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md)。
界面与交互基线见 [docs/UI_DESIGN_SPEC.md](docs/UI_DESIGN_SPEC.md)。
电子书现状、能力缺口和分阶段补齐计划见 [docs/EBOOK_GAP_ANALYSIS.md](docs/EBOOK_GAP_ANALYSIS.md)。

## 本地构建

DevEco Studio 打开本目录即可同步工程。命令行验证：

```bash
/Applications/DevEco-Studio.app/Contents/tools/hvigor/bin/hvigorw \
  --mode module \
  -p module=entry@default \
  -p product=default \
  assembleHap \
  --no-daemon
```

打包使用 `assembleHap`。仓库不保存个人签名证书或密码；安装到设备前请在 DevEco Studio 中配置本地签名。

## 部署验收

真实MyTools部署可使用`app/scripts/run-deployment-acceptance.sh`串行验证认证令牌轮换、远程媒体短期票据与Range响应，以及Copilot SSE。账号密码、远程账号ID和测试媒体路径只通过环境变量提供；生成的`app/build/acceptance/*.json`权限为600，只记录目标origin、UTC时间、检查状态和退出码，不记录凭据、JWT、票据、路径或响应正文。完整环境变量和单项命令见[开发状态](docs/DEVELOPMENT_STATUS.md)。

签名HAP和HarmonyOS设备准备好后，可执行：

```bash
MYTOOLS_DEVICE_HAP=/absolute/path/to/entry-default-signed.hap \
./app/scripts/run-device-acceptance.sh
```

该命令验证签名、安装、冷启动和进程存活，并生成脱敏设备证据；它不替代登录、远程播放、阅读和Copilot的页面场景验收。多设备在线时需额外设置`MYTOOLS_DEVICE_TARGET`。

## 工程目录

```text
app/
├── AppScope/
├── entry/
├── features/
│   ├── auth/
│   ├── media/
│   ├── reader/
│   ├── copilot/
│   ├── tools/
│   └── profile/
├── shared/
│   ├── core/
│   ├── data/
│   ├── network/
│   ├── storage/
│   └── ui/
├── native/
│   └── agent-bridge/
└── docs/
```

首个可运行版本使用单 HAP、多源码目录，边界稳定后再将 `features/` 和 `shared/` 拆为 HAR/HSP 模块。
