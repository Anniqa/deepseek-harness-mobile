# DSH 手机版（dsh-mobile）

> 本仓库是 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 的 fork，新增 Android 手机版（[`dsh-mobile/`](dsh-mobile/)）。上游代码保持原样，手机版相关改动都在 `dsh-mobile/` 目录内。

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的 Android 手机版：App 内置 proot + Ubuntu 22.04 容器，容器内运行 Node.js + `@deepseek-ai/dsh web`，用 WebView 加载其 Web UI 并注入移动端适配样式，界面为 DeepSeek 同款风格。

## 下载

见 [Releases](../../releases) 页面，下载最新 `app-release.apk` 直接安装。

## 功能

- **内置 Ubuntu 容器**：proot 运行完整 Ubuntu 22.04 (aarch64) rootfs，首启联网下载（约 200MB：rootfs + Node.js + dsh）
- **干净工作目录**：dsh 的 HOME 与 cwd 是容器内独立的 `/home/dsh`（不用 /root，避免安装残留与 npm 缓存污染工作区选择器）
- **SD 卡目录映射**：外置 SD 卡 bind 到容器 `/mnt/sd` 和工作目录内 `/home/dsh/sd`；`/sdcard/dsh-shared` 兜底映射到 `/mnt/shared` 与 `/home/dsh/shared`，打开工作区即可看到 sd/ shared/ 两个入口，方便传入/传出文件
- **手机版界面**：注入的 `mobile.css` 强制 dsh Web UI 适配手机屏幕比例（弹窗不溢出、详情面板按内容三态门控、轨迹事件详情面板整宽覆盖、触控目标加大、代码块横向滚动、设置弹窗单列化、消息页高度链锁死内部滚动、token/耗时统计条自动折行完整显示、悬浮提示气泡弹出 2.5 秒后自动消失）
- **侧栏非常驻**：左侧 rail 默认隐藏，左上角悬浮按钮或屏幕左缘右滑呼出覆盖式抽屉，点遮罩、抽屉上左滑或选中会话自动收起
- **DeepSeek 风格**：启动屏/安装向导/设置页均为 DeepSeek 品牌风格（品牌蓝 #4D6BFE、圆角卡片）
- **前台服务保活**：容器由前台服务持有，通知栏可查看状态/停止
- **命令沙箱已禁用**：容器启动时钉死 `DSH_PERMISSION_MODE=danger-full-access`（proot 里 bwrap/Landlock 基本不可用，workspace-write 会报 SANDBOX_UNAVAILABLE）；dsh 的 bash/文件写入不设围栏、不逐条询问。会话里仍可手动切回 workspace-write/read-only，但沙箱 runner 不可用时受限命令会失败

## 使用

1. 安装 APK，首次启动进入安装向导，等待下载安装完成
2. 自动进入 Web 界面（127.0.0.1:3080），按引导配置 API key 即可使用
3. 需要 SD 卡映射：设置 → 开启「所有文件访问权限」→ 选择外置 SD 目录 → 重启服务
4. 容器内访问宿主机文件：`/mnt/sd`（外置 SD）、`/mnt/shared`（共享存储 dsh-shared）；工作目录 `/home/dsh` 下的 `sd/` 与 `shared/` 是同样两个入口
5. 注意：v1.0.7 起 dsh 的 HOME 从 /root 迁到 /home/dsh，升级后需重新填一次 API key

## 构建

环境：JDK 17+、Android SDK 35、Gradle 9.5+（自带 wrapper）。

```bash
cd dsh-mobile
./gradlew :app:assembleRelease
# 输出 app/build/outputs/apk/release/app-release.apk
```

注意：本仓库 `gradle.properties` 中 `android.aapt2FromMavenOverride=/usr/bin/aapt2` 是 aarch64 Linux 主机的 workaround，x86_64 主机请删除该行。签名配置在 `app/build.gradle.kts`（自带调试级 keystore，正式发布请更换）。

## 结构

- `BootstrapInstaller` — 下载/解压 rootfs、proot（Termux apt deb）、Node.js，容器内 `npm i -g @deepseek-ai/dsh`
- `NodePtyFixer` — node-pty 原生模块（pty.node）自检与 node-gyp 重建（安装时与服务启动前双兜底）
- `ProotRunner` — 组装 proot 命令（bind /dev /proc /sys /tmp /mnt/sd /mnt/shared）启动 `dsh web`
- `HarnessService` — 前台服务持有容器进程，退出自动重启（上限 5 次）
- `MainActivity` + `MobileUiInjector` — WebView + 注入 `assets/mobile.css` / `assets/inject.js`
- `SettingsActivity` — SD 映射、端口、下载镜像、日志、重置

## 上游

DeepSeek Harness 本体（CLI / Web UI / 插件体系）见上游仓库 [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) 及本仓库其余目录。
