# Privacy policy for ylih

Last updated: 25 July 2026

## The short version

ylih does not collect, transmit, or share any data. It has no internet permission, so it is not
merely a promise — the app is technically incapable of sending anything anywhere.

## What the app stores

Everything ylih records is written to a database on your device and stays there:

- the identity Android reports for each audio device it sees (a name such as
  "WH-1000XM4", the connection type, and the last two octets of the hardware address);
- one row per connection, holding when it started, when it ended, and why it ended;
- optionally, how much of that connected time was playing audio;
- anything you type in yourself, such as a pair's name, the price you paid, or the reason you
  retired it.

No account is required, and there is nothing to sign in to.

## What the app does not do

- No data is sent off the device. The app declares no `INTERNET` permission.
- No analytics, crash reporting, advertising, or tracking of any kind.
- No third-party SDKs that collect data.
- Nothing is sold or shared with anyone.
- No location data is collected. Bluetooth access is used only to be told that a headset
  connected or disconnected and to read its name; it is never used to determine your location.

## Permissions and why they exist

| Permission | Why |
|---|---|
| Nearby devices (`BLUETOOTH_CONNECT`) | To receive Bluetooth connect and disconnect events and read a headset's name. Without it, the app's core function does not work. |
| Notifications (`POST_NOTIFICATIONS`) | Only to show the silent notification that optional detailed tracking requires. |
| Run at startup (`RECEIVE_BOOT_COMPLETED`) | To close sessions that a shutdown never reported, so a reboot does not corrupt your totals. |
| Foreground service | Only while optional detailed tracking is enabled. Android delivers wired-headphone plug events only to an app that is already running. |

## Your data, in your hands

- **Export.** Settings → Export writes your entire history as readable JSON to a file you choose.
- **Import.** Settings → Import replaces the stored data with a backup.
- **Deletion.** Deleting a pair inside the app deletes its sessions. Uninstalling the app deletes
  everything, unless you tick "keep app data" in Android's uninstall dialog — then it stays on the
  phone for a reinstall, and clearing the app's storage in Settings removes it. Because nothing is
  ever uploaded, there is no copy anywhere else for us to delete.
- **Backup.** Android's own backup system is enabled, so your history can be restored to a new
  phone. That transfer is handled by Android and your Google account settings, not by ylih.

## Children

ylih is not directed at children and collects no data from anyone, of any age.

## Changes

If this policy ever changes, the new version will be published at this address and the date at
the top will be updated.

## Contact

dpfuturehacker@gmail.com
