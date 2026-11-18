# Just Print It

An Android app for the **HP Ink Tank / Smart Tank 580** at home. It talks to the printer over your Wi-Fi with the
standard driverless protocols (IPP Everywhere for printing and status, eSCL for scanning), so it needs no HP account
and no cloud.

## Features

**Status**
- Live printer state, refreshed every 5 seconds while the screen is open: ready, printing, out of paper, jam, cover open…
- Ink level of every tank, with the low-ink line and a warning when a tank runs low
- The printer's own lifetime page counter (colour / black & white / scans), when the printer shares it
- Button that opens the printer's web page

**Printing**
- PDFs and photos (pick in the app, or *Share → Just Print It* from any app)
- Page ranges (`1-3, 5, 8-`)
- 1, 2, 4, 6, 9 or 16 pages per sheet (rotated automatically to fit best)
- Colour or black & white
- Zoom 25–300 %
- **Trim margins**: crops white borders so small PDFs are enlarged to fill the page
- Paper size (A4, Letter, Legal, A5, A6, 4×6, 5×7, L), only showing sizes the printer accepts
- Quality: Draft / Normal / Best, plus copies
- Photos: fit or fill the page, and borderless when the printer supports it
- Live preview of each sheet before printing

**Double-sided**
- Odd pages only / even pages only
- Guided two-step double-sided printing: the app prints the fronts, shows step-by-step instructions to put the
  stack back in, then prints the backs in the right order and orientation. Order, rotation and odd-page handling
  can be adjusted in Settings, and a 2-page test sheet checks the setup.

**Scanning**
- Scan from the glass to PDF (multi-page) or JPG
- A4, Letter or 4×6; colour or grey; 150 / 300 / 600 dpi
- Save to any folder, share, or print the scan
- **Copy**: scan and print in one tap

**Counters**
- Pages printed today, this month and all time, split into colour and black & white
  (each page is checked for colour, so a black-text page printed in "colour" mode counts as black & white)
- 30-day chart, monthly totals, and a history of recent jobs

**When a job fails**
- The app shows what went wrong (out of paper, jam, lost connection…) and how many pages the printer confirmed.
- It **never retries on its own**. You choose: print the remaining pages, print everything again, or don't retry.
  Failed jobs stay in history so you can retry later (after asking you first).
- Under the hood, documents are sent in a way that stops Android from silently re-sending them after a dropped connection.

**Also included**
- Automatic printer discovery on Wi-Fi, or enter the IP address
- Keeps printing when you switch to another app
- Interrupted jobs (app closed mid-print) are marked in history and can be retried

## Install

Every push builds the app on GitHub Actions. Open the repository's **Actions** tab → latest *Build APK* run →
download **JustPrintIt-apk**, unzip, and install `app-release.apk` on your phone (allow "install unknown apps"
for your browser or file manager). Android 8.0 or newer.

## Build it yourself

Requires JDK 17 and the Android SDK (Android Studio is the easiest way).

```sh
./gradlew :app:assembleRelease     # APK in app/build/outputs/apk/release/
./gradlew :core:test               # protocol and layout tests
./gradlew -PcoreOnly :core:test    # same, without needing the Android SDK
```

## How it's put together

- `core/` — plain Kotlin, no Android: IPP encoder/decoder and client, PWG raster encoder, page-range parser,
  N-up layout, margin trimming, double-sided sheet planning, eSCL scan client, scan-to-PDF writer, HP usage
  counters, and counter statistics. Unit-tested, including against an in-process fake printer.
- `app/` — the Android app (Jetpack Compose): screens, PDF/photo rendering, the print queue, job history (SQLite).

## Notes

- Ink tank printers estimate ink levels; the window on each tank is the real reading.
- The phone and printer must be on the same Wi-Fi network. A fixed IP for the printer (set in the router) helps.
