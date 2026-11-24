# Quick Print (Android)

Fast, ad-free replacement for the HP Smart app, built for the HP Smart Tank 580 (works with most AirPrint/Mopria printers).
No login, no ads, no cloud.

## Install on your phone

1. On the phone, open **https://github.com/786byte/Hp-Printer/releases/latest**
2. Tap **QuickPrint.apk**, open the download, and allow installing from your browser if Android asks
   (Play Protect may warn about an unknown developer — choose **Install anyway**).
3. Open **Quick Print**, tap **Find printer** (or **Enter IP**: `192.168.10.158`).

Android 10 or newer. Every push to the repository builds a new release.

### Keeping updates installable (one-time setup)

Android only installs an update over the old app when both are signed with the same key. The key
(`app/quickprint.keystore`) is **not** stored in the repository. Add it to GitHub once as a secret:

1. On a computer: `base64 -w0 quickprint.keystore` (macOS: `base64 -i quickprint.keystore`) and copy the output.
2. GitHub → this repository → **Settings → Secrets and variables → Actions → New repository secret**.
3. Name: `QUICKPRINT_KEYSTORE_B64`, value: the copied text. Save.

Until the secret exists, each build is signed with a temporary key: it installs fine on a phone without
Quick Print, but you'd have to uninstall before installing a newer build (which clears counters — save a
backup file first).

## Features

- Finds the printer on Wi-Fi (mDNS) or enter its IP once
- Live ink levels and printer status (IPP, with HP LEDM as fallback)
- Print PDFs and photos: page range, 1/2/3/4/6/9 per sheet, B/W, A4 / Legal / Letter / A5 / 4×6 / 5×7,
  portrait / landscape, zoom 25–400 %, trim white margins, border around each page, photo fill, copies, presets
- **Print queue**: choose several PDFs/photos and tap *Add to queue* — each file becomes its own job and they
  print one after another. Every job shows a live badge: *Waiting → Printing → Done ✓ / Failed ✗ / Did it print?*,
  with a progress bar and a summary such as "5 jobs: 3 done · 1 printing · 1 failed".
  If a job fails or its result is unclear, the queue **stops and asks you**: retry it, skip it, or stop.
  Failed jobs keep a *Retry…* button (asks first). Nothing is retried automatically.
- Odd / even pages only, and guided manual double-sided (fronts, reload, backs) with reversed order,
  long- or short-edge flip, and a blank padding sheet. A 4-page **double-sided test** and a
  "turn back sides upside down" setting adapt it to how the 580 feeds paper.
- Paper size check: reads the PDF's page size, selects it, and asks if you choose a different paper
  ("PDF is Legal, print on Legal or A4?") — for single prints and for each queued file
- Counters: colour vs B/W, today / this month / all time, plus the printer's own lifetime counter (HP LEDM)
- Never retries a failed job by itself; asks "Did it print?" whenever the result is unclear
- **Resume where it stopped**: while printing, the app saves how many sheets the printer has finished. If a job
  fails, choose *Resume from sheet N* (or type how many came out) and only the rest prints — for single prints,
  each side of guided double-sided, and queue jobs. An *Unfinished print* card survives crashes and restarts.
- **Any network**: works on the home Wi-Fi or on the printer's own Wi-Fi Direct, and finds the printer again
  automatically when its address changes (remembered addresses, 192.168.223.1, Wi-Fi search). Mobile data can stay
  on. *Printer's own Wi-Fi (Wi-Fi Direct)…* joins the printer's network from inside the app; on phones that support
  two Wi-Fi connections the home Wi-Fi stays connected too.
- Stability: no reused HTTP connections (HP printers drop idle ones), errors shown instead of crashing, and if the
  app ever crashes the details are kept so you can copy and send them
- Scan from the glass to PDF or JPG, several pages
- Share a file to Quick Print from any app
- Backup: Android auto-backup to your Google account, plus save/restore a backup file (e.g. to Google Drive)

## Sending modes

- **Direct to printer** (default): IPP to `http://<printer>:631/ipp/print`. Sends the original PDF when nothing
  needs changing (sharpest text), otherwise PWG raster or Apple URF at 300 dpi. Follows the job with
  Get-Job-Attributes. The queue always uses this mode.
- **Android print screen** (fallback): Android's print system with the HP / Mopria print service.

## Still to verify on the real 580

Built and compiled, but not yet run against the printer:
IPP on `/ipp/print`, which formats it accepts (PDF / PWG / URF), job-state polling, eSCL scanning, the LEDM
counter and ink XML, and the manual double-sided order (use the test page).

## Build it yourself

Open this folder in Android Studio and press Run, or `./gradlew assembleRelease` with JDK 17 and the Android SDK.
