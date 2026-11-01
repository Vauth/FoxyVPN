<div align="center">

# FoxyVPN - Unofficial Firefox VPN Client

<p align="center">
  <img src="https://i.ibb.co/8D9KdmNk/6a07263d16c0.png" alt="FoxyVPN Logo" width="150"/>
</p>

![Android](https://img.shields.io/badge/Android-212121?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-212121?style=for-the-badge&logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-212121?style=for-the-badge&logo=android&logoColor=white)
![Sing-Box](https://img.shields.io/badge/Sing--Box-212121?style=for-the-badge&logo=go&logoColor=white)
![License](https://img.shields.io/badge/License_MIT-212121?style=for-the-badge)

*FoxyVPN is a modern, privacy-focused Android client for Mozilla's Firefox VPN service. Powered by a customized sing-box core, it connects directly to Mozilla's global Fastly edge network using high-performance MASQUE protocol with HTTP/2 and HTTP/3 support.*

</div>

<br>

## ✨ Features

### Core & Privacy
- **Firefox Account Integration**
- **System-Wide Tunneling**
- **Split Tunneling**
- **DNS-over-HTTPS (DoH)**
- **Local Network Bypass**
- **Proxy-Only Mode**

### Performance & Censorship Resistance
- **TLS Fragmentation**
- **uTLS Fingerprint Emulation**
- **Upstream Proxy Chaining**
- **Custom Edge Override**
- **TCP Fast Open (TFO)**
- **Multiplexing Controls**
- **Custom Tunnel MTU**

### User Experience & System Integration
- **Real-Time Diagnostics**
- **Public & Exit IP Detection**
- **Global Locations**
- **Quick Settings Tile & Widget**
- **In-App Auto-Updates**
- **Diagnostic Logging**

<br>

## 📸 Screenshots

<div align="center">
  <img src="https://i.ibb.co/cK1L8wGk/90a25df7e3c6.jpg" alt="Screenshot 1" width="30%">
  <img src="https://i.ibb.co/tMff3B7C/198e4f622a58.jpg" alt="Screenshot 2" width="30%">
  <img src="https://i.ibb.co/G3V0CwCb/8524a888adee.jpg" alt="Screenshot 3" width="30%">
</div>

<br>

## 🚀 Getting Started

1. **Get a Firefox account**: If you don't already have one, create an account at [accounts.firefox.com](https://accounts.firefox.com).
2. **Download & Install**: Grab the latest APK matching your device (`arm64-v8a`, `armeabi-v7a`, or `universal`) from the [Releases](https://github.com/Vauth/FoxyVPN/releases) page.
3. **Sign In**: Tap "Sign in with Firefox" to authenticate via the in-app browser.
4. **Connect**: Choose a server location and tap the power button.

> [!NOTE]
> Access, entitlement, quota, and geographic availability are controlled by Mozilla. FoxyVPN connects directly to official endpoints and respects account quotas.

<br>

## 🛠 Building from Source

### Prerequisites
- JDK 17
- Android SDK (API 35, Build-Tools 35.0.0)
- Gradle 8.10.2

### Build Instructions
1. Clone the repository:
   ```bash
   git clone https://github.com/Vauth/FoxyVPN.git
   cd FoxyVPN
   ```

2. Download and verify the pinned sing-box core (`libbox.aar`):
   ```bash
   mkdir -p app/libs
   curl -L -o app/libs/libbox.aar https://github.com/Vauth/sing-box/releases/download/v1.15.0-foxy-masque.2/libbox.aar
   echo "f1b59f4fab207b4c19f8ea3d145bc4dd0e834a530081d0dc045f4b795d6eac0b  app/libs/libbox.aar" | sha256sum --check --strict
   ```

3. Build the APKs:
   ```bash
   gradle :app:assembleDebug
   ```
   Output APKs will be located in `app/build/outputs/apk/debug/`.

<br>

## ✍️ Acknowledgements

- [sing-box](https://github.com/SagerNet/sing-box) - the universal proxy platform
- [firefox-vpn-client](https://github.com/UjuiUjuMandan/firefox-vpn-client) - the Go reference client

<br>

## 🛠 Contributing

Contributions are welcome! Feel free to submit a pull request or report an issue.

<br>

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
