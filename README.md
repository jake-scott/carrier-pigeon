# Carrier Pigeon

An Android home-screen widget that shows which SIM is currently carrying your mobile data, so you can see when your phone automatically switches between eSIMs.

Licensed under the [Apache License 2.0](LICENSE).

## Status: proof of concept

This build is a spike to confirm, on a real phone, that every automatic data SIM switch is reported. It contains:

- A widget showing the active data SIM's name, colour, carrier and network type, plus a "temporary" tag when data has moved off your default data SIM. Tap it to refresh.
- An app screen with the current state, all active SIMs, and a switch log you can share.

## How it detects switches

| Path | When it runs |
| --- | --- |
| `ConnectivityManager.registerNetworkCallback(request, PendingIntent)` | The system wakes the app whenever a cellular internet network comes up, even if the app isn't running. No polling, no background service. |
| `TelephonyCallback.ActiveDataSubscriptionIdListener` | Instant updates while the app's process is alive (Android 12+). |
| Boot / app update receiver, widget refresh every 30 min, widget tap | Safety nets. |

Every path ends in `DataSimRefresher`, which reads `SubscriptionManager.getActiveDataSubscriptionId()`, writes the result to DataStore and redraws the widget. The widget itself only reads stored state.

## Testing the spike

1. Install the debug APK from the latest GitHub Actions run (artifact `carrier-pigeon-debug`), or build with `./gradlew assembleDebug`.
2. Open the app and allow phone access.
3. Add the "Data SIM" widget to your home screen.
4. Use the phone normally for a day, ideally somewhere coverage changes, or force a switch by making a call on the non-data SIM.
5. Open the app and tap **Share log**. Lines marked `SWITCH` are switches; `reason=` shows which path reported it, and `network=` cross-checks the sub behind the cellular network.

## Building

Requires JDK 17 and the Android SDK (compileSdk 36). `./gradlew assembleDebug`.
