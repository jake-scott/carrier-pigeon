# Carrier Pigeon

An Android home-screen widget that shows which SIM is currently carrying your mobile data, so you can see when your phone automatically switches between eSIMs.

Licensed under the [Apache License 2.0](LICENSE).

## Status: proof of concept

This build is a spike to confirm, on a real phone, that every automatic data SIM switch is reported. It contains:

- A widget. Top line: the carrier carrying mobile data, marked **default** (your data SIM in Settings) or **backup** (the phone moved data to another SIM). Bottom line: how the phone is reaching the internet right now: Wi-Fi, 5G, LTE, 3G, 2G or No data, with "+ VPN" when the default route goes over a VPN. The dot is the SIM's colour, grey when that SIM can't carry data (airplane mode, mobile data off, no service). Tap it to refresh.
- An app screen with the current state, all active SIMs, and a switch log you can share.

## How it detects switches

| Path | When it runs |
| --- | --- |
| `ConnectivityManager.registerNetworkCallback(request, PendingIntent)` | The system wakes the app whenever a cellular internet network comes up, even if the app isn't running. No polling, no background service. |
| `TelephonyCallback.ActiveDataSubscriptionIdListener` | Instant updates while the app's process is alive (Android 12+). |
| `SubscriptionManager.OnSubscriptionsChangedListener` | While the process is alive: an eSIM enabled, disabled or renamed. |
| `CARRIER_CONFIG_CHANGED` and default-data broadcasts | Manifest receiver, even when the app isn't running: an eSIM enabled or disabled, default data SIM changed. |
| Airplane mode broadcast, service-state and mobile-data listeners | Show "Airplane mode", "Mobile data off" or "No service" instead of a SIM that can't carry data. |
| A second `PendingIntent` network callback for any internet network, VPNs included, plus an in-process default-network callback | Keeps the bottom line (Wi-Fi / mobile / VPN) current. Losing Wi-Fi while the app's process is dead is only caught at the next event or the 30-minute refresh. |
| Boot / app update receiver, widget refresh every 30 min, widget tap | Safety nets. |

Every path ends in `DataSimRefresher`, which reads `SubscriptionManager.getActiveDataSubscriptionId()`, writes the result to DataStore and redraws the widget. The widget itself only reads stored state.

## Testing the spike

1. Install the debug APK from the latest GitHub Actions run (artifact `carrier-pigeon-debug`), or build with `./gradlew assembleProdDebug`.
2. Open the app and allow phone access.
3. Add the "Data SIM" widget to your home screen.
4. Use the phone normally for a day, ideally somewhere coverage changes, or force a switch by making a call on the non-data SIM.
5. Open the app and tap **Share log**. Lines marked `SWITCH` are switches and `NOTE` lines record process starts and listener registration; `reason=` shows which path reported it, and `network=` cross-checks the sub behind the cellular network.

## Building

Requires JDK 17 and the Android SDK (compileSdk 36). `./gradlew assembleProdDebug` builds a debug APK ("Carrier Pigeon Debug", its own package, installs beside any other build).

Release builds are signed in CI with keys in Cloud KMS and are reproducible for F-Droid. See [docs/signing.md](docs/signing.md).
