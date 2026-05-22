# ScreenGuard — Android Studio Setup Guide

## What this app does
- **Screen Time tab**: Shows how many minutes/hours you spend in each app, for today / last 7 days / last month
- **Filter tab**: Toggle a local VPN that intercepts DNS queries and blocks adult/harmful websites

---

## Step 1 — Create the project in Android Studio

1. Open Android Studio → **New Project**
2. Choose **Empty Views Activity**
3. Set these values:
   - Name: `ScreenGuard`
   - Package name: `com.screenguard`
   - Language: **Kotlin**
   - Minimum SDK: **API 26 (Android 8.0)**
4. Click **Finish**

---

## Step 2 — Add the files

Copy every file from this zip into your project, matching the folder structure exactly.

The key folders:
```
app/src/main/
├── AndroidManifest.xml
├── java/com/screenguard/
│   ├── ui/
│   │   ├── MainActivity.kt
│   │   ├── screens/UsageFragment.kt
│   │   ├── screens/FilterFragment.kt
│   │   ├── components/AppUsageAdapter.kt
│   │   └── components/BlocklistAdapter.kt
│   ├── service/
│   │   ├── DnsVpnService.kt
│   │   └── BootReceiver.kt
│   └── utils/
│       ├── UsageStatsHelper.kt
│       └── BlocklistManager.kt
└── res/
    ├── layout/
    │   ├── activity_main.xml
    │   ├── fragment_usage.xml
    │   ├── fragment_filter.xml
    │   ├── item_app_usage.xml
    │   └── item_blocked_domain.xml
    ├── menu/
    │   └── bottom_nav_menu.xml
    └── values/
        ├── strings.xml
        ├── colors.xml
        └── themes.xml
```

---

## Step 3 — Update app/build.gradle

Replace the contents of `app/build.gradle` with the provided file.

Then in the **project-level** `build.gradle` (the one at root, not app/), add JitPack for the chart library:

```groovy
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }  // ← add this line
    }
}
```

If you're using **settings.gradle** (newer projects), add it there instead:
```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }  // ← add this
    }
}
```

Click **Sync Now** when Android Studio prompts.

---

## Step 4 — Run the app

1. Connect your Android phone via USB (or use an emulator — but emulator won't have real app usage data)
2. Press the green **Run** button
3. The app will open and immediately redirect you to **Usage Access settings**

---

## Step 5 — Grant permissions (IMPORTANT)

### Usage Access (for screen time)
The app will open Settings automatically. Find **ScreenGuard** in the list and toggle it **ON**.

If it doesn't open automatically:
`Settings → Apps → Special app access → Usage access → ScreenGuard → Allow`

### VPN Permission (for content filter)
When you toggle the filter ON in the app, Android shows a system dialog:
> "ScreenGuard wants to set up a VPN connection"

Tap **OK**. This is normal — it's a local VPN, your traffic doesn't go to any external server.

---

## How the content filter works

```
Your phone                        This app
─────────────────────────────────────────────────────
App requests example.com
    ↓
DNS query → tun0 (local VPN interface)
    ↓
DnsVpnService reads the packet
    ↓
Is "example.com" in the blocklist?
    ├── YES → reply with NXDOMAIN (blocked!)
    └── NO  → forward to 1.1.1.1 → relay response back
```

No traffic leaves your device to any third-party server. It's all local.

---

## Adding more blocked sites

Two ways:
1. **In the app**: Filter tab → "+ Add Domain" → type the domain
2. **In code**: Add to `DEFAULT_BLOCKED_PATTERNS` set in `BlocklistManager.kt`

For a production-grade blocklist (100,000+ domains), you can use:
- Steven Black's hosts file: https://github.com/StevenBlack/hosts
- CleanBrowsing DNS (swap UPSTREAM_DNS in DnsVpnService to `185.228.168.10`)

---

## Common issues

| Problem | Fix |
|---------|-----|
| "No usage data" | Grant Usage Access permission in Settings |
| Filter toggle does nothing | Tap OK on the VPN permission dialog |
| Build fails on JitPack | Add maven { url 'https://jitpack.io' } to repositories |
| App crashes on launch | Check Logcat — most likely a missing resource reference |

---

## Next features to add
- Daily time limits per app (with notifications when limit is hit)
- Scheduled blocking (e.g. block social media after 10pm)
- Password protection so the filter can't be turned off easily
- Import a large hosts-file blocklist from URL
