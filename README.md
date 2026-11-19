# Carrier Pigeon

An Android app and home-screen widget that shows which SIM is carrying your mobile data and how your phone is reaching the internet right now, so you can see when it quietly switches between SIMs or drops from Wi-Fi to mobile data.

Application ID `dev.jakethesnake.carrierpigeon`. Android 11 (API 30) or later. Made for phones with a SIM; it also installs on devices without one (tablets, Chromebooks), where it shows only the internet and Wi-Fi status. English, French and Spanish.

Licensed under the [Apache License 2.0](LICENSE).

## Trademarks

The Apache License covers the code, not the "Carrier Pigeon" name or the pigeon icon. Forks need a different name and icon. See [TRADEMARKS.md](TRADEMARKS.md) for the details and the list of excluded artwork files.

## What it shows

### The app

- **Internet headline.** One line at the top says how the phone is online: "Internet via" Wi-Fi (with the network name) or a SIM's carrier, or Ethernet, Bluetooth, USB, with "+ VPN" when the default route goes over a VPN. It says "No internet" or "Airplane mode is on" when that's the case.
- **A tile for each active SIM.** Each tile shows the slot, the SIM's name, its carrier and network, live signal bars, and whether it's your **default** data SIM or a **backup** the phone moved data to. The border shows how the tile relates to the internet. Solid green means it's carrying the internet now. Dotted green means it's on standby and would take over if Wi-Fi dropped. Grey means it isn't involved. A SIM that can't carry data says why: airplane mode, mobile data off, or no service.
- **A Wi-Fi tile.** Shows the network name, security, band, live signal bars and link speed, with the same border scheme. Android only reveals the Wi-Fi name to apps with precise location access. The app asks for it only to show the name, and explains why.
- **Network labels the way the status bar shows them:** **5G SA**, **5G NSA**, **5G+ NSA** (high-band NR), **LTE-A Pro** (sometimes branded "5G E", but it's LTE), **LTE+** (carrier aggregation), **LTE**, 3G or 2G. 5G NSA means the phone is registered on LTE with 5G added on top, which is why apps that read the raw network type say LTE. Turn on **Show network detail** to also see what's underneath, such as "LTE + NR".
- **Tap a term** such as LTE, 5G, NSA or SA for a short explanation.
- **The menu** at the top right has Refresh, the switch log (view, share, clear) and, on Android 13 and later, the app's language.

### The widget

The "Data SIM" widget's top line names the carrier carrying mobile data, marked **default** or **backup**. Its bottom line says how the phone is reaching the internet: Wi-Fi, a mobile network, or No data, plus "+ VPN". The dot is the SIM's colour, and turns grey when that SIM can't carry data. Tap the widget to open the app, which also refreshes it. The widget follows the phone's language, and the app's language if you've set one.

## How it detects switches

| Path | When it runs |
| --- | --- |
| `ConnectivityManager.registerNetworkCallback(request, PendingIntent)` | The system wakes the app whenever a cellular internet network comes up, even if the app isn't running. No polling, no background service. |
| `TelephonyCallback.ActiveDataSubscriptionIdListener` | Instant updates while the app's process is alive (Android 12+). |
| `SubscriptionManager.OnSubscriptionsChangedListener` | While the process is alive: an eSIM enabled, disabled or renamed. |
| `CARRIER_CONFIG_CHANGED` and default-data broadcasts | Manifest receiver, even when the app isn't running: an eSIM enabled or disabled, default data SIM changed. |
| Airplane mode broadcast, service-state and mobile-data listeners | Show "Airplane mode", "Mobile data off" or "No service" instead of a SIM that can't carry data. |
| A second `PendingIntent` network callback for any internet network, VPNs included, plus an in-process default-network callback | Keeps the bottom line (Wi-Fi / mobile / VPN) current. Losing Wi-Fi while the app's process is dead is only caught at the next event or the 30-minute refresh. |
| Boot / app update receiver, widget refresh every 30 min, opening the app (or tapping the widget, which opens it) | Safety nets. |

Every path ends in `DataSimRefresher`, which reads `SubscriptionManager.getActiveDataSubscriptionId()`, writes the result to DataStore and redraws the widget. The widget itself only reads stored state.

## Installing

- **Google Play.** Internal testers get each build from `main`. Each `vX.Y.Z-rc.N` tag builds the tagged commit as version X.Y.Z for closed testers, and production gets that same build when the commit is tagged `vX.Y.Z`.
- **Dev builds (Firebase App Distribution).** Each push to `dev` goes to the App Distribution tester group as "Carrier Pigeon Dev" (`dev.jakethesnake.carrierpigeon.dev`). It installs beside the Play build, so you can run both.
- **GitHub releases.** Each `vX.Y.Z` tag publishes a signed APK on the releases page, and F-Droid builds it from the same tag.
- **Debug APK.** Every GitHub Actions run of `build.yml` attaches a debug build (artifact `carrier-pigeon-debug`, "Carrier Pigeon Debug"), signed with the throwaway debug key.

On first launch, allow phone access so the app can read your SIM names and network types. Carrier Pigeon never makes or manages calls, even though Android's prompt mentions them. Location access is optional and only shows the Wi-Fi name. Then add the "Data SIM" widget from your home screen's widget picker.

### Reporting a switch problem

Open the menu and tap **Share log**. Lines marked `SWITCH` are data SIM switches. `NOTE` lines record process starts and listener registration. `reason=` shows which detection path reported each line, and `network=` cross-checks the SIM behind the cellular network.

## Building

Requires JDK 17 or later (CI uses 21) and the Android SDK (compileSdk 36).

- `./gradlew assembleProdDebug` builds a debug APK ("Carrier Pigeon Debug", `dev.jakethesnake.carrierpigeon.debug`), which installs beside any other build.
- `./gradlew testProdDebugUnitTest lintProdDebug` runs the unit tests and lint, as CI does.
- `scripts/check_hardcoded_strings.sh` fails if UI code has an English string that isn't in `res/values`. Translations are in `res/values-fr` and `res/values-es`.

Release builds are signed in CI with keys in Cloud KMS and are reproducible for F-Droid. See [docs/signing.md](docs/signing.md).

### Release configuration

`release.yml` reads which Google Cloud project, keys, accounts and apps to use from Actions variables (Settings → Secrets and variables → Actions → Variables), so a fork doesn't inherit this repository's infrastructure. None of them are secrets: the Workload Identity bindings are what protect the keys. The values come from `tofu output` in mobile-build-infra (`build/` and `bootstrap/`).

Most are set on the GitHub environments (Settings → Environments → *name* → Environment variables), each under the same name with that environment's value. The rest are shared, so set them once at repository level. A variable set on an environment overrides a repository variable with the same name.

Each job that needs variables checks them first. When any are missing it warns and does nothing, and the jobs that depend on it are skipped, so a fork with no variables still builds and leaves the unsigned build in the run's artifacts.

| Variable | Where | Value |
| --- | --- | --- |
| `GCP_WIF_PROVIDER` | repository | The Workload Identity provider: `projects/<number>/locations/global/workloadIdentityPools/github/providers/github` |
| `PLAY_PACKAGE` | repository | The app's package name on Google Play |
| `KMS_KEY` | `dev`, `qa`, `prod` | The Cloud KMS key that environment signs with, `projects/<project>/locations/<region>/keyRings/<ring>/cryptoKeys/<key>`: the dev key in `dev`, the Play upload key in `qa`, the release key in `prod` |
| `SIGNER` | `dev`, `qa`, `prod` | The service account allowed to sign with that key |
| `FIREBASE_DISTRIBUTOR` | `dev` | The account that uploads to Firebase App Distribution (bootstrap/ output `distribute_service_account`) |
| `FIREBASE_APP_ID` | `dev` | The Firebase Android app, `1:<project number>:android:<hex>` (build/ output `firebase_android_app_ids`) |
| `FIREBASE_TESTER_GROUP` | `dev` | The App Distribution tester group alias |
| `PLAY_PUBLISHER` | `qa`, `prod` | The Play account for that environment: in `qa` the tester (build/ output `play_testers`; "Release apps to testing tracks" on this app only), in `prod` the publisher (`play_publishers`; production release rights on this app only) |

From the command line: `gh variable set KMS_KEY --env dev --body 'projects/…'`, or without `--env` for a repository variable. See [docs/signing.md](docs/signing.md) for how the keys and accounts are set up.
