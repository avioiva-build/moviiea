# Moviiea — Android App

Official Android app for the Moviiea website (https://moviiea.blogspot.com/).

This is a native Android WebView app with:
- Splash screen with the app icon
- Pull-to-refresh
- Offline screen with a Retry button
- Proper back-button navigation inside the app
- External links (other websites) open in the phone browser

## Where things live

| What | Where |
|------|-------|
| Website address used by the app | `app/src/main/java/com/moviiea/app/MainActivity.kt` → `HOME_URL` (top of file) |
| App name shown under the icon | `app/src/main/res/values/strings.xml` |
| Colors (red / dark navy) | `app/src/main/res/values/colors.xml` |
| Automatic APK builder | `.github/workflows/build.yml` (runs on GitHub Actions) |

## Building the APK

You do NOT need Android Studio. The included GitHub Actions workflow
(`.github/workflows/build.yml`) builds the APK automatically on every push.

Steps: push/upload this project to a GitHub repository → open the **Actions**
tab → wait for the run to finish → download the **Moviiea-app** artifact →
install `app-debug.apk` on any Android 5.0+ phone.

See the full illustrated guide: **Moviiea-App-Build-Guide.pdf**.
