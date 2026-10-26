# V2Tab (v2ray Multi-Node Balancer)

> **Simple, intelligent multi-node proxy client with dynamic load balancing, automatic failover, and multi-connection speed acceleration.**
>
> *By Dr. Arash*

---

## 🚀 Overview

**V2Tab** is an advanced Android proxy client powered by the **Xray-core** engine. Unlike standard clients that route traffic through only one node at a time, V2Tab aggregates multiple proxy configurations (such as **VLESS**, **VMess**, **Trojan**, and **Shadowsocks**) into a dynamic weighted balancer pool.

By multiplexing outbound traffic across multiple functional configs simultaneously, V2Tab:
- **Accelerates connections** through multi-path concurrency
- **Eliminates downtime** with active health checking & automated failover
- **Maximizes throughput** by dynamically shifting traffic away from high-latency or choked nodes

---

## ✨ Key Features

- **Multi-Node Concurrency & Load Balancing**: Run and balance across dozens of VLESS / VMess / Shadowsocks servers simultaneously.
- **Dynamic Balancing Strategy**:
  - `round-robin` / `leastPing` / `leastLoad`
  - Automated health probes via Xray-core Observatory
- **Full Protocol Support**:
  - VLESS (Reality, XTLS, TLS, WebSocket, gRPC)
  - VMess (TCP, WS, gRPC)
  - Trojan & Shadowsocks
- **One-Click Subscription Import**: Paste standard subscription URLs or Base64 config bundles.
- **Native Xray-core Engine**: Directly links against optimized `libv2ray.aar` with TUN interface routing and process UID resolution.
- **Live Topology & Real-Time Telemetry**: Live canvas showing traffic flows, per-node uplinks/downlinks, and latency graphs.
- **Built for Android 7.0+ (API 24 to 36)** with modern Jetpack Compose and Material 3 UI.

---

## 📦 Download APK

Direct download from GitHub Releases:
- **[Download V2Tab Release APK (`v2tab.apk`)](https://github.com/arashmh13/v2tab/releases/download/v1.0.0/v2tab.apk)** — *65.5 MB (Optimized release build, signed & ready to install)*
- **[Download V2Tab Debug APK (`v2tab-debug.apk`)](https://github.com/arashmh13/v2tab/releases/download/v1.0.0/v2tab-debug.apk)** — *77.2 MB (Debug build with verbose logs)*

---

## 🛠️ Building from Source

### Prerequisites
- JDK 17+
- Android SDK (API 34+)
- Android Studio Ladybug / Meerkat or Gradle CLI

### Command Line Build

Clone the repository and build using Gradle:

```bash
# Clone the repository
git clone https://github.com/arashmh13/v2tab.git
cd v2tab

# Build Release APK
./gradlew assembleRelease

# Build Debug APK
./gradlew assembleDebug
```

The compiled APKs will be located in:
- Release: `app/build/outputs/apk/release/app-release.apk`
- Debug: `app/build/outputs/apk/debug/app-debug.apk`

---

## 🧩 Architecture

```text
       ┌────────────────────────┐
       │     Android UI /       │
       │   Jetpack Compose      │
       └───────────┬────────────┘
                   │
       ┌───────────▼────────────┐
       │   BalancerVpnService   │
       │     (VpnService)       │
       └───────────┬────────────┘
                   │ TUN Interface
       ┌───────────▼────────────┐
       │       Xray-core        │
       │ (Observatory+Balancer) │
       └─────┬─────┬─────┬──────┘
             │     │     │
    ┌────────▼┐ ┌──▼──┐ ┌▼────────┐
    │ VLESS 1 │ │VLESS│ │ VLESS 3 │ ... (Multi-Node Concurrency)
    └─────────┘ └───┬─┘ └─────────┘
                    ▼
                Internet
```

---

## 📄 License

Open source under the [MIT License](LICENSE).

---
*Developed with ❤️ by Dr. Arash*

