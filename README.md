<div align="center">

<img src="readme-image.png" width="120" alt="JMAPJolt icon" />

# JMAPJolt

**A fast, open source JMAP email client for Android.**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/version-0.1-green.svg)](https://github.com/FalseEnvironment/JMAPJolt/releases)
[![Platform](https://img.shields.io/badge/platform-Android-brightgreen.svg)](#)
[![Min SDK](https://img.shields.io/badge/minSdk-24-orange.svg)](#)

<br />

<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/FalseEnvironment/JMAPJolt">
  <img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="48" alt="Get it on Obtainium" />
</a>

</div>

---

## Why JMAPJolt?

JMAPJolt was born from a simple thought: there are far too few mobile apps with JMAP support. JMAP is a modern, efficient alternative to IMAP, with faster syncing, less overhead, and better push notifications, yet hardly any Android clients take advantage of it. I built JMAPJolt to help close that gap and give the protocol the lightweight, no-nonsense client I wanted to use myself.

## Features

| | |
|---|---|
| **Multiple accounts** | Switch between accounts from the drawer |
| **Custom themes** | Gray, light, and OLED dark, plus an accent color picker |
| **Swipe actions** | Configurable per direction (delete, archive, mark read, spam) |
| **Compose** | Rich text editor with a formatting toolbar and attachments |
| **UnifiedPush** | Push notifications without Google services, with automatic distributor discovery |

## Download

Get the latest APK from the [Releases](https://github.com/FalseEnvironment/JMAPJolt/releases) page, or add the repo to [Obtainium](https://github.com/ImranR98/Obtainium) using the button above.

## Verify

You can verify the APK's signing certificate with [AppVerifier](https://github.com/soupslurpr/AppVerifier) or `apksigner`:

```bash
apksigner verify --print-certs JMAPJolt-x.y.apk
```

| | |
|---|---|
| **Package name** | `com.falseenvironment.jmapjolt` |
| **SHA-256** | `F3:60:21:74:79:CB:0B:58:51:85:A0:63:AC:34:66:BC:EA:74:C6:E0:19:FF:8F:BB:3E:9E:23:C3:24:66:E2:38` |

## Build from source

```bash
git clone https://github.com/FalseEnvironment/JMAPJolt.git
cd JMAPJolt
./gradlew assembleDebug
```

## AI-assisted development

This project was developed with the assistance of AI code generation tools.

## License

Released under the [GNU General Public License v3.0](LICENSE).
