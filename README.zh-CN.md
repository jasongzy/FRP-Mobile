<p align="center">
  <img src="docs/app-icon.svg" width="144" alt="FRP Mobile 图标">
</p>

<h1 align="center">FRP Mobile</h1>

[English](README.md) | 简体中文

用于管理 frpc 和 frps 的原生 Android 应用。

## ✨ 功能

- Material 3 原生界面
- 独立运行多份客户端和服务端配置
- 支持语法高亮和快捷输入的 TOML 配置编辑器，支持管理 TLS 证书
- 日志浏览与搜索
- 配置导入与导出
- 通过前台服务保持连接，支持开机自启、在最近任务中隐藏
- 在应用内管理可选的 Magisk 守护模块
- 快捷设置磁贴、Tasker 插件、令牌授权广播及按需授权的配置接口

## 📥 下载

从 [GitHub Releases](https://github.com/jasongzy/FRP-Mobile/releases/latest) 下载最新通用 APK。

通过 CI 自动跟进上游 frp 稳定版并发布更新。

## 🚀 使用

打开 App 后，新建或导入配置，填写服务器信息，然后从控制台启动即可。

守护模块需要 root 和 Magisk。安装后由模块接管连接，之后可独立于 App 持续运行。在设置中停用或卸载模块即可回到 App 前台运行模式。

## 🤝 致谢

- [frp](https://github.com/fatedier/frp)
- [Tasker Plugin Library](https://github.com/joaomgcd/TaskerPluginSample)
- [sky22333/frp-android](https://github.com/sky22333/frp-android)
- [AceDroidX/frp-Android](https://github.com/AceDroidX/frp-Android)
- [jahen/frp](https://github.com/jahen/frp)
- [CHERWIN_FRPC](https://github.com/CHERWING/CHERWIN_FRPC)
