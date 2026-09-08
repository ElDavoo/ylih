# Privacy policy for ylih

Last updated: 5 September 2026

## The short version

ylih collects, transmits, and shares no data. It has no internet permission, so this isn't a
promise — the app can't send anything anywhere.

## What the app stores

Everything ylih records is written to a database on your device and stays there:

- the identity Android reports for each audio device (a name such as "WH-1000XM4", the
  connection type, and the last two octets of the hardware address);
- one row per connection: when it started, when it ended, and why;
- optionally, how much of that connected time was playing audio;
- anything you type in, such as a pair's name, its price, or why you retired it.

No account, no sign-in.

## What the app does not do

- No data leaves the device. The app declares no `INTERNET` permission.
- No analytics, crash reporting, advertising, or tracking.
- No data-collecting third-party SDKs.
- Nothing is sold or shared.
- No location data. Bluetooth access only learns that a headset connected or disconnected and
  reads its name — never to locate you.

## Permissions and why they exist

| Permission | Why |
|---|---|
| Nearby devices (`BLUETOOTH_CONNECT`) | Receives Bluetooth connect/disconnect events and reads a headset's name — without it the app can't work. |
| Notifications (`POST_NOTIFICATIONS`) | Shows the silent notification detailed tracking requires. |
| Run at startup (`RECEIVE_BOOT_COMPLETED`) | Closes sessions a shutdown never reported, so a reboot doesn't corrupt your totals. |
| Foreground service | Used only when detailed tracking is on — Android delivers wired-headphone plug events only to an already-running app. |

## Your data, in your hands

- **Export.** Settings → Export writes your entire history as readable JSON to a file you choose.
- **Import.** Settings → Import replaces the stored data with a backup.
- **Deletion.** Deleting a pair deletes its sessions. Uninstalling deletes everything unless you
  tick "keep app data", which keeps it for a reinstall until you clear storage in Settings.
  Nothing is uploaded, so there's no copy elsewhere to delete.
- **Backup.** Android's own backup system is enabled, so history can be restored to a new phone —
  handled by Android and your Google account settings, not by ylih.

## Assistants (Android 17 and later)

Android 17 lets apps publish read-only functions an on-device assistant can call. ylih publishes
two: lifetime hours per pair, and listening totals for today, seven days and thirty days. Both
ship **disabled** in the schema, so the system offers them to nothing until you turn on
"assistant access" in Settings; turning it off removes them from the system too.

Neither function can change or delete anything, or expose more than those hours. What an
assistant does with figures you've given it is up to that assistant, not ylih — why access stays
off by default.

## Children

ylih is not directed at children and collects no data from anyone of any age.

## Changes

If this policy changes, the new version is published at this address with an updated date above.

## Contact

dpfuturehacker@gmail.com
