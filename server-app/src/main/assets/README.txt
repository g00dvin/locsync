Place the LocSync client APK here as `locsync-client.apk` for the hidden
head-unit bootstrap screen's "Install LocSync client" button.

The file is intentionally not committed (built artifact). The release build/CI
should copy client-app's assembled APK to this path before packaging server-app.
Without it, the "Install LocSync client" button reports "client APK not found";
the "Install APK…" (user-picked) path works regardless.
