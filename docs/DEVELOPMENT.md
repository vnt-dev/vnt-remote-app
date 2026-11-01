# 开发与发布

本文面向开发者和发布维护者。安装和使用说明见 [README](../README.md)。除注明在 Rust 工程执行的命令外，以下命令均在 Android 项目根目录执行。

## 项目结构与原生库来源

Android 端使用 Kotlin/XML 界面、Java JNI/音频适配，以及 Rust 网络与会话核心。

Rust 核心位于独立仓库 `vnt-dev/vnt-remote`。GitHub Actions 默认检出该仓库的 `master` 到 `rust-core/`，通过 `-Pvnt.rustDir` 交给 Gradle。Gradle 调用 cargo-ndk，从 `crates/vnt-android`（Cargo 包 `vnt-android`）及其依赖编译 `libvnt_remote_android.so`，分别生成 `arm64-v8a` 和 `x86_64` 库并打入 APK。源码仓库和版本可通过下文的 Actions 参数覆盖；产物中的 `build-info.txt` 记录实际提交 SHA。

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

## GitHub Actions 打包

工作流为 `.github/workflows/build-apks.yml`：推送 `v*` 标签时构建正式签名的 Release，构建和校验成功后自动创建对应的 GitHub Release，并上传两个 APK、`SHA256SUMS` 和 `build-info.txt`，自动生成发布说明。重跑同一标签时更新已有 Release 的同名附件。发布使用工作流自带的 `GITHUB_TOKEN`，构建任务已声明 `contents: write` 权限，无需额外发布 Token。普通分支推送不会触发打包。

也可在 **Actions → Build Android APKs → Run workflow** 手动选择 `debug` / `release`。手动构建只有在选择 `v*` 标签且构建类型为 `release` 时才发布 GitHub Release；其余情况仅上传 Artifacts。所有构建的产物均可在该次运行的 **Artifacts** 下载，保留 30 天。

每次仅输出 `arm64-v8a`、`x86_64` 两个独立 APK，以及 SHA-256 校验和、Android/Rust 提交信息。当前仅支持这两种 64 位架构。工作流运行 `testDebugUnitTest`（当前 AGP 默认仅为 Debug 创建单元测试任务），并检查每个 APK 的签名、实际 ABI、JNI 导出和 16 KB 对齐。标签不会自动修改应用版本；发布前需在 `app/build.gradle.kts` 递增 `versionCode`、更新 `versionName`，并使用与 `versionName` 一致的 `vMAJOR.MINOR.PATCH` 标签（例如 `v0.2.0`）。设置页通过当前仓库的 GitHub Releases latest API 检查正式版，以语义版本比较标签与实际安装的 `versionName`，不按发布时间判断升级。预发布不会推送给正式版用户。保留现有 APK 附件命名，以便按设备 ABI 选择下载。下载通过外部浏览器完成，安装仍由用户确认。

在仓库 **Settings → Secrets and variables → Actions** 配置下列参数。

| 类型 | 名称 | 内容 |
| --- | --- | --- |
| Secret | `ANDROID_KEYSTORE_BASE64` | 正式签名 `.jks` / `.keystore` 文件的 Base64（整个文件） |
| Secret | `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| Secret | `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| Secret | `ANDROID_KEY_PASSWORD` | 签名密钥密码 |
| Secret（可选） | `VNT_RUST_TOKEN` | Rust 仓库私有时必填：具有该仓库 Contents 只读权限的 fine-grained PAT；组织需批准时先完成批准 |
| Variable（可选） | `VNT_RUST_REPOSITORY` | Rust 源码仓库，默认 `vnt-dev/vnt-remote` |
| Variable（可选） | `VNT_RUST_REF` | Rust 分支、标签或提交 SHA，默认 `master`；正式发布建议固定完整提交 SHA |

Debug 不需要四个签名 Secret。Release 缺少任意一个签名参数会立即失败。私有 Rust 仓库不能只依赖本仓库自动生成的 `GITHUB_TOKEN`；公开 Rust 仓库无需配置 Token。手动运行的 `rust_ref` 可以覆盖 `VNT_RUST_REF`。

已有正式签名证书请继续使用，以保证用户可以覆盖安装。尚无证书时，在本地交互式生成并妥善备份（命令会询问密码）：

```sh
keytool -genkeypair -v -keystore vnt-release.jks -alias vnt-remote \
  -keyalg RSA -keysize 3072 -validity 10000
# macOS：复制 Base64 到剪贴板，粘贴为 ANDROID_KEYSTORE_BASE64
base64 -i vnt-release.jks | tr -d '\n' | pbcopy
# Linux：输出 Base64，自行复制
base64 -w 0 vnt-release.jks
```

上例 `ANDROID_KEY_ALIAS` 为 `vnt-remote`，两个密码按生成时的实际值填写。密钥文件、密码和 Base64 内容均不要提交到 Git。CI 的签名信息通过环境变量注入，且禁用 Gradle configuration cache。需要在本地生成同样的两个独立 APK 时，使用 `./gradlew -Pvnt.splitApks=true :app:assembleDebug`；不传该参数仍只生成原有通用 APK。

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
