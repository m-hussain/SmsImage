# SMS Image (Android, no Wi-Fi/Bluetooth)

Sends a compressed image as a series of ordinary single-segment SMS. The other phone
(same app) reassembles it, saves it to Pictures/SMSImage and notifies you.

## Get the APK (no Android Studio needed)
1. Create a free GitHub account, make a new repository, upload everything in this folder
   (keep the `.github` folder).
2. Open the repo > Actions > "Build APK" > Run workflow (it also runs on every upload).
3. When it finishes (~3-5 min) open the run and download the artifact `SmsImage-debug-apk`.
   Unzip it, copy app-debug.apk to both phones and install.

Alternative: open the folder in Android Studio and press Run / Build APK.

## First run on each phone
- Allow SMS + Notifications.
- Android 13+ for sideloaded apps: Settings > Apps > SMS Image > (3 dots) > "Allow restricted settings",
  then allow SMS permission again.
- Tecno/Vivo/Xiaomi: turn on Autostart and set Battery to "Unrestricted" for this app,
  and never force-stop it, or the receiver can't wake up.

## How it works
1. Resize + lossy WebP until it fits the chosen size (Tiny 4 KB ... Large 32 KB).
2. Deflate (used only if it actually makes the data smaller).
3. Ascii85-style encoding into 85 GSM-7-safe characters (4 bytes -> 5 chars).
Each SMS = `@IMG` + type (H header / D data / F footer) + 2-char transfer id + 2-char part number + 150
chars (~120 bytes), so it is always exactly one 160-char SMS. Header carries part count, size and
deflate flag; footer carries a CRC32. Parts can arrive in any order and duplicates are harmless.

~8 KB image = about 70 SMS. Sending is paced at one SMS per 2.2 s.
