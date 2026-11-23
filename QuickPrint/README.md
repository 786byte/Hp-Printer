# Quick Print (Android)

Fast, ad-free replacement for the HP Smart app, built for the HP Ink Tank 580 (works with most AirPrint/eSCL printers).

- Finds the printer on Wi-Fi (or enter its IP once)
- Live ink levels and status (IPP)
- Print PDFs/photos: page range, 1/2/3/4/6/9 pages per sheet, B/W, paper size, portrait/landscape, borders, photo fill — sent through Android's print system (HP Print Service Plugin / Mopria)
- Odd / even pages only, and a guided double-sided flow (front sides, reload, back sides; long or short edge flip)
- Custom zoom % and "trim white margins" to blow up small PDFs
- Pages-printed counters (today / month / all time, colour vs B/W)
- Failed jobs are never retried automatically: the app asks you first
- **Direct to printer** (IPP): sends jobs itself, follows each job to the end, asks "Did it print?" if the printer goes quiet
- Works with mobile data on: printer traffic is pinned to Wi-Fi
- Reads the PDF's page size (A4 / Legal / Letter...), selects it, and asks if you pick a different paper
- Printer's own lifetime page counter (HP LEDM), when the printer reports it
- Backup: automatic Android backup to your Google account + save/restore a backup file (e.g. Google Drive)
- Fixed signing key (app/quickprint.keystore) so new builds install over old ones

## Get the APK (no Android Studio needed)
1. Create a free GitHub account and a new empty repository.
2. Upload everything in this folder (keep the `.github` folder) to it.
3. Open the repo's **Actions** tab → "Build APK" → wait ~4 minutes.
4. Open the finished run → download the **QuickPrint-apk** artifact (zip) → unzip → install `app-debug.apk` on your phone (allow "install unknown apps").

Or open the folder in Android Studio and press Run.

## Notes
- Source was written without being compiled in the authoring environment; if the build shows an error, send the log and it'll be fixed quickly.
- minSdk 29 (Android 10+).
