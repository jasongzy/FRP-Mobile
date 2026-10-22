<p align="center">
  <img src="docs/app-icon.svg" width="144" alt="FRP Mobile icon">
</p>

<h1 align="center">FRP Mobile</h1>

English | [简体中文](README.zh-CN.md)

A native Android app for managing frpc and frps.

## ✨ Features

- Material 3 native interface
- Run multiple client and server configurations independently
- TOML editor with syntax highlighting, quick symbol input and TLS certificate management
- Browse and search logs
- Import and export configurations
- Keep connections running through a foreground service, with boot start and the option to hide the app from Recents
- Manage an optional Magisk daemon module within the app
- App icon quick actions, home screen shortcuts and widgets, plus a Quick Settings tile, Tasker plugin, token-authorized broadcasts and an opt-in configuration API

## 📥 Download

Download the latest universal APK from [GitHub Releases](https://github.com/jasongzy/FRP-Mobile/releases/latest).

CI automatically follows stable upstream frp releases and publishes updates.

## 🚀 Usage

Open the app, create or import a profile, enter your server details, then start it from the console.

The daemon module requires root and Magisk. Once installed, it manages connections and runs independently of the app. Disable or uninstall it in Settings to return to the app's foreground service.

## 🤝 Acknowledgements

- [frp](https://github.com/fatedier/frp)
- [Tasker Plugin Library](https://github.com/joaomgcd/TaskerPluginSample)
- [sky22333/frp-android](https://github.com/sky22333/frp-android)
- [AceDroidX/frp-Android](https://github.com/AceDroidX/frp-Android)
- [jahen/frp](https://github.com/jahen/frp)
- [CHERWIN_FRPC](https://github.com/CHERWING/CHERWIN_FRPC)
