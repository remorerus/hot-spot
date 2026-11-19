# Hot Spot for Rokid Glasses

Android companion app and AIUI voice agent for switching between saved Wi-Fi networks on Rokid Glasses.

The GitHub Actions workflow builds a debug APK from the `android/` project and publishes it as the `Hot-Spot-APK` artifact. Source files are being added to this repository; a successful workflow run is required before an APK can be downloaded.

Configured voice targets:
- `Studio`
- `Local_Network_5G` (spoken as “Local Network”)

The app only switches to networks already saved in the system and confirms the resulting SSID before reporting success.
