---
title: Carrier Pigeon privacy policy
permalink: /privacy
---

# Carrier Pigeon privacy policy

_Last updated: 16 November 2026_

Carrier Pigeon (`dev.jakethesnake.carrierpigeon`) is an Android app and home-screen widget that shows which SIM is carrying your mobile data and how your phone is reaching the internet. This policy explains what information the app uses and what happens to it.

## The short version

Carrier Pigeon collects nothing. It has no accounts, no ads, no analytics and no crash reporting, and it does not include Google Play services or any other third-party SDK. The app does not even request Android's internet permission, so it cannot send anything off your phone by itself. Everything it reads stays on your device, unless you choose to share the diagnostic log.

## What the app reads, and why

**Phone state (`READ_PHONE_STATE`).** Used to read your SIM cards' display names, carrier names, SIM slots, which SIM is your default and which is carrying mobile data, the mobile network type (such as LTE or 5G), and whether mobile data, service or airplane mode are on. Carrier Pigeon never makes, receives or manages calls, even though Android's permission prompt mentions calls. It does not read your phone number, IMEI, IMSI or SIM serial number.

**Network and Wi-Fi state (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`).** Used to see whether you are online through Wi-Fi, a mobile network, Ethernet, Bluetooth, USB or a VPN, and to show your Wi-Fi connection's security, band, signal and link speed.

**Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`), optional.** Android only tells apps the name (SSID) of the Wi-Fi network you're connected to if they have precise location access. Carrier Pigeon asks for it only so it can show that name, and only when you choose to from the Wi-Fi tile. The app never reads your location, coordinates or GPS position. It only checks whether the phone's Location setting is on, because Android hides the Wi-Fi name while it's off. The Wi-Fi name is shown on screen and is not saved. You can decline or revoke this permission at any time; everything else keeps working.

**Start at boot (`RECEIVE_BOOT_COMPLETED`).** Used so the widget keeps updating after the phone restarts.

## What is stored on your phone

- **The latest state** shown by the widget: the data SIM's name, carrier, slot, colour, network type, connection type and the time of the last update.
- **Your settings:** whether to show network detail, the widget's language, and whether the app has already asked for each permission.
- **A diagnostic switch log** of up to the 500 most recent events. Each line has a timestamp, the SIM's display name and Android's internal ID numbers for the SIMs involved, the network type, the connection type (for example Wi-Fi or mobile) and whether a VPN was on. It does not contain the Wi-Fi network name, your location, or your phone number.

This data lives in the app's private storage. You can clear the log from the app's menu at any time, and uninstalling the app or clearing its storage deletes all of it.

If Android's own backup is turned on for your device, Android may include this app data in your device backup. That backup is made and stored by Android and your backup provider (usually Google), under their privacy policy, not by Carrier Pigeon.

## Sharing the log

The menu has a **Share log** option to help report problems. Nothing is shared unless you tap it. When you do, Android's share sheet opens with the log as plain text, and you choose which app (for example email or a messaging app) to send it with, and to whom. Once you send it, that app and the recipient handle it under their own terms.

## Children

Carrier Pigeon is not directed at children and collects no personal information from anyone.

## Changes

If this policy changes, the updated version will be posted on this page with a new date. A change that means data leaves your phone would only come with a new version of the app, and this page would say so first.

## Contact

Questions about this policy or the app can be raised on the project's GitHub page: <https://github.com/jake-scott/carrier-pigeon/issues>.
