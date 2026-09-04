<div align="center">

# FoxyVPN - Unofficial Firefox VPN client

*FoxyVPN signs in with a Firefox account, obtains a proxy pass from Mozilla's
Guardian service, and carries the whole device's traffic through Firefox VPN's
Fastly edge over an HTTP/2 tunnel. It is a native Kotlin app with a
Jetpack Compose (Material 3) UI.*

</div>

<br>

## 🔎 Features

- **System-wide VPN**
- **Firefox account sign-in**
- **Multiple Servers**
- **Split tunneling**
- **Private DNS by default**
- **Encrypted DNS (DNS over HTTPS)**
- **Upstream proxy chaining**
- **Proxy-only mode**
- **Dark / light / follow-system theme**
- **Exit verification**
- **In-app logs**

<br>

## 📸 Screenshots
<div align="center">
  <img src="https://i.ibb.co/hx3Gh7d9/91728322b4ad.jpg" alt="Screenshot 1" width="30%">
  <img src="https://i.ibb.co/zVFc07CD/b6b8e29ec4ac.jpg" alt="Screenshot 2" width="30%">
  <img src="https://i.ibb.co/ZzP5mgfq/dc50132fb3cb.jpg" alt="Screenshot 3" width="30%">
</div>

<br>

## 🚀 How to use?

1. **Get a Firefox account.** If you do not have one, create it for free at
   [accounts.firefox.com](https://accounts.firefox.com/signup).
2. **Install the APK** from the [Releases](https://github.com/vauth/foxyvpn/releases)
   page.
3. **Sign in** with that account's email and password.
4. **Pick a location** and tap the power button.

> [!NOTE]
> **No subscription is required.** FoxyVPN runs on the free **50 GB of monthly
VPN traffic Mozilla includes with a Firefox account** for browser users, which
is the same allowance the Firefox browser's built-in VPN uses. Once the
allowance is used up for the month, connections stop working until it resets.

<br>

## 🗃 Requirements

- Android 8.0 (API 26) or newer
- JDK 17
- Android SDK 35 and NDK `26.1.10909125`
- `git` on `PATH`

<br>

## ⚙️ Build
* #### Clone Repository:
```bash
git clone https://github.com/vauth/foxyvpn.git
cd foxyvpn
   ```
* #### Build and Install:
```bash
gradle :app:assembleRelease --no-daemon
```

<br>

### 📝 To-Do List
The project is on test phase so not much yet.
- [ ] Improve stability.
- [ ] HTTP/3 implementation.
- [ ] Better Logo.

<br>

## ✍️ Acknowledgements

- [firefox-vpn-client](https://github.com/UjuiUjuMandan/firefox-vpn-client) - the Go reference client this app is a port of
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) - the native tun2socks engine
- [Conscrypt](https://github.com/google/conscrypt) - ALPN-capable TLS on every supported API level

<br>

## 🛠 Contributing
Contributions are welcome! Feel free to submit a pull request or report an issue.

<br>

## 📄 License
This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
