# Carrier Pigeon

An Android home-screen widget that shows which SIM is currently carrying your mobile data, so you can see when your phone automatically switches between eSIMs.

Licensed under the [Apache License 2.0](LICENSE).

## Trademarks

The Apache License covers the code, not the "Carrier Pigeon" name or the pigeon icon. Forks need a different name and icon. See [TRADEMARKS.md](TRADEMARKS.md) for the details and the list of excluded artwork files.

## Status: proof of concept

This build is a spike to confirm, on a real phone, that every automatic data SIM switch is reported. It contains:

- A widget. Top line: the carrier carrying mobile data, marked **default** (your data SIM in Settings) or **backup** (the phone moved data to another SIM). Bottom line: how the phone is reaching the internet right now: Wi-Fi, a mobile network, or No data, with "+ VPN" when the default route goes over a VPN. The mobile network is shown the way the status bar sees it (turn on "Show network detail" in the app to also see what is underneath it, e.g. "LTE + NR"): **5G SA**, **5G NSA** (LTE + NR: registered on LTE with 5G added on top, which is why other apps that read the raw network type say LTE), **5G+ NSA** (high-band NR), **LTE-A Pro** (sometimes branded "5G E", but LTE), **LTE+** (carrier aggregation), **LTE**, 3G or 2G. The dot is the SIM's colour, grey when that SIM can't carry data (airplane mode, mobile data off, no service). Tap it to open the app, which also refreshes the widget.
- An app screen with a tile for each active SIM: its slot, name, carrier and network, with the SIM carrying mobile data outlined in green. Tap a term such as LTE, 5G, NSA or SA for a short explanation. Refresh and the switch log (view, share, clear) are in the menu at the top right.

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

## Testing the spike

1. Install the debug APK from the latest GitHub Actions run (artifact `carrier-pigeon-debug`), or build with `./gradlew assembleProdDebug`.
2. Open the app and allow phone access when it asks. (If you add the widget first, it says "Tap to allow phone access"; tapping it opens the app and asks.)
3. Add the "Data SIM" widget to your home screen.
4. Use the phone normally for a day, ideally somewhere coverage changes, or force a switch by making a call on the non-data SIM.
5. Open the app, open the menu at the top right and tap **Share log**. Lines marked `SWITCH` are switches and `NOTE` lines record process starts and listener registration; `reason=` shows which path reported it, and `network=` cross-checks the sub behind the cellular network.

## Building

Requires JDK 17 and the Android SDK (compileSdk 36). `./gradlew assembleProdDebug` builds a debug APK ("Carrier Pigeon Debug", its own package, installs beside any other build).

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
| `PLAY_PUBLISHER` | `qa`, `prod` (or repository) | The Play publisher account: a Play Console user with release rights on this app only, bound to the `qa` and `prod` environments on `main` |

From the command line: `gh variable set KMS_KEY --env dev --body 'projects/…'`, or without `--env` for a repository variable. See [docs/signing.md](docs/signing.md) for how the keys and accounts are set up.
