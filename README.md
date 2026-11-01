# VNTRemote Android

原生安卓主控：Kotlin/XML 界面 + Java JNI/音频适配 + Rust 网络与会话核心。只提供主控功能。

## 已实现

- TCP、TCP+UDP、直连 QUIC、VNT 网络配置（手动/订阅）和在线节点选择。
- 原生 MediaCodec → SurfaceView 视频输出；H.264、HEVC、AV1 按设备能力协商。硬件解码器不可用时排除失败实现，最终回退系统 H.264 软件解码。
- 一台设备、单屏显示与切换；触控板/直接触控、外接键鼠、快捷键、中文文本粘贴、文本剪贴板和远程声音。
- 有界媒体队列、FEC、参考链修复、会话恢复；后台暂停本地音画并释放按键，前台恢复请求关键帧。
- Android Keystore AES-GCM 加密配置。会话恢复令牌仅在 Rust 内存中保存。

Android 7.0 / API 24 起，支持 arm64-v8a 真机与 x86_64 模拟器。没有图片/文件传输、多台设备同时连接或多屏同显。

## 构建

安装当前稳定版 Rust、Android SDK 37、NDK 28.2.13676358、JDK 25。此工程使用 AGP 内置 Kotlin 支持。

```sh
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
sdkmanager 'platforms;android-37.0' 'ndk;28.2.13676358'
```

在未纳入版本控制的 `local.properties` 中设置两个工程的位置：

```properties
sdk.dir=/absolute/path/to/Android/sdk
vnt.rustDir=/absolute/path/to/vnt-remote
```

也可通过 `-Pvnt.rustDir=/path/to/vnt-remote` 和 `ANDROID_HOME` 构建。未配置 Rust 路径时，默认寻找 `../../RustroverProjects/vnt-remote`。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
python3 scripts/check-native.py app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle 自动运行 cargo-ndk，生成两种 ABI。Debug 的 Rust 代码也使用 opt-level=2；Release 使用 Rust workspace 的 LTO/单代码生成单元配置。不同变体的 Cargo 缓存与 JNI 输出相互独立，Rust 源码和 Cargo.lock 参与增量检查。APK 和 ELF 均检查 16 KB 对齐；构建时拒绝未解析的 native 符号。

正式发布使用 `:app:assembleRelease`，并按发行流程提供签名；不要将 Debug 签名用于生产。

本次交付的 `app/build/outputs/apk/release/vnt-remote-preview.apk` 使用发布优化与本机 Debug 证书签名，可直接安装体验；`app/build/outputs/apk/debug/app-debug.apk` 用于调试。

## 界面

安卓端与桌面端使用相同的 VNT 标识、蓝色主色、卡片、状态标签和菜单命名。手机使用抽屉侧边栏，宽屏常驻侧栏。

- **远程控制**：直接填写设备地址和访问密码，或从已保存/同组设备中选择；高级选项提供协议、编码、画质、分辨率、音频与剪贴板配置。
- **设备列表**：通过完整页面添加和编辑设备，单独保存连接配置并一键连接。
- **设置**：浅色、深色、跟随系统；远程画面始终深色。
- **服务端**：通过完整页面管理 VNT 手动/订阅配置、房间、在线状态及本机连接 ID。

## 使用

1. 直连：在“远程控制”填写电脑地址（可附加端口，默认 29875）和访问密码，选择连接协议。
2. VNT：在“服务端”保存配置后手动启动，在“远程控制”选择在线节点并填写该电脑的访问密码。组网密码和被控密码是两个不同的凭据。
3. 进入远程画面后固定横屏，其他页面固定竖屏。远控操作栏点击“全屏”会隐藏顶部信息和系统栏；全屏时使用画面外右侧工具栏的“操作”展开操作栏，右侧“退出全屏”或返回键恢复普通显示。底部不再重复显示退出全屏或屏幕选择按钮。
4. 单指仍用于触控板（移动、轻点点击）或直接触控（点击定位、滑动拖动、长按右键）。触控板模式右下角提供半透明悬浮鼠标：左右键支持点击、长按，按住后滑动当前手指或用另一根手指滑动画面可拖动远端光标；拖动鼠标底部只调整图标位置，位置在本次会话内保留。默认双指滑动发送远端滚轮；开启右侧“移动画面”开关后，双指滑动仅平移本地画面，切换时会提示当前操作方式。两种模式下都可以双指捏合缩放，捏合不会发送滚轮。
   操作栏提供“适应窗口”，切换触控模式时会提示操作方式。手动倍率、查看位置及移动画面开关会在旋转、全屏切换及后台恢复后保留；新连接默认关闭移动画面。切换远端屏幕或分辨率变化时重新适配。
5. “发送文本”通过协议向被控端请求粘贴，支持中文输入。被控端先写入文本剪贴板，再按自身系统执行粘贴（Windows：Ctrl+V，macOS：Command+V），并返回执行结果。请求不会在断线恢复后自动重试；该操作会替换远端剪贴板文本。
6. 剪贴板仅在前台同步；向下获取的是远端最近发布的文本，需先在远端复制。仅观看会话禁用输入和剪贴板。

后台保留连接属于尽力维护：没有前台服务或后台声音，系统可以挂起或终止进程。被终止后需重新连接。Android 17+ 首次使用需允许系统的本地网络权限。

## 回归与合成链路测试

在 Rust 工程运行：

```sh
cargo test -p vnt-proto -p vnt-net -p vnt-link -p vnt-client -p vnt-android --lib
cargo test -p vnt-net --test overlay --test video_repair
cargo test -p vnt-host --lib
cargo test -p vnt-remote --lib
cargo run -p vnt-remote --example android_fixture
```

合成服务只发布生成画面和音频，不读取真实桌面、注入输入或写系统剪贴板；监听 39875，测试密码为 `android-test`，十分钟后退出。不要将此测试服务暴露到公网。

在 Android 工程运行：

```sh
./gradlew :app:connectedDebugAndroidTest
# 开着上述合成服务时，模拟器通过 10.0.2.2 访问宿主机：
adb shell am instrument -w -e fixtureHost 10.0.2.2 \
  com.rustvnt.remote.test/androidx.test.runner.AndroidJUnitRunner
# 加入十次重连与 native 内存/线程回落检查：
adb shell am instrument -w -e fixtureHost 10.0.2.2 -e reconnects 10 \
  -e class com.rustvnt.remote.ControllerInstrumentedTest#syntheticHostStreamsAcrossTransportsAndSurfaceRecreation \
  com.rustvnt.remote.test/androidx.test.runner.AndroidJUnitRunner
```

仪器测试覆盖 Keystore、JNI 参数校验/旧句柄、连接取消、Activity 重建；提供 fixtureHost 时还覆盖 TCP、混合模式、QUIC 的实际原生解码，以及后台/前台和 Surface 重建。

## 性能复测

在能力支持 1080p60 硬解的真机上使用 Release，记录手机型号、系统版本、解码器、电脑编码器、传输方式和网络条件。预热后采样至少 60 秒，记录显示输出 FPS、队列等待与解码耗时、RTT、码率、CPU、温升和 `adb shell dumpsys meminfo com.rustvnt.remote`；重复十次连接/断开检查内存和线程能否回落。

界面的 FPS 是提交到 Surface 的解码输出数量；解码耗时从输入提交计至输出取出，队列等待从网络帧交付计至输入提交。它们不等于物理屏幕呈现延迟或完整远控端到端延迟。请用 Perfetto / 录屏时间标记另测完整链路。

模拟器结果仅用于功能验证。真机 1080p60、HEVC/AV1、真实键鼠/IME、Windows 锁屏/UAC、多屏热插拔和真实 VNT 订阅/中继仍应在对应设备与服务环境验收。
