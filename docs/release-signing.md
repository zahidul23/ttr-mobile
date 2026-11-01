# Signing and package names

| Build | Package | Name on the phone | Key |
|---|---|---|---|
| Release (`python tools/build_app.py`) | `help.toontown.ttrmobile` | TTR Mobile | your release key |
| Test build (`--dev-server URL`) | `help.toontown.ttrmobile.dev` | TTR Mobile Dev | `.tools/ttr-development.jks`, made automatically |

The two install side by side, each with its own accounts and game files.

## Release key

A release build signs with the key at `TTR_RELEASE_KEYSTORE` (default `~/.ttr-mobile/release.p12`, alias
`ttr-mobile`). The password comes from `TTR_RELEASE_PASSWORD` if set; otherwise apksigner asks for it. A release
build stops if the keystore is missing; it never falls back to the test key.

Make one with the JDK's keytool (it asks for the password):

    keytool -genkeypair -v -storetype PKCS12 -keystore %USERPROFILE%\.ttr-mobile\release.p12 -alias ttr-mobile ^
        -keyalg RSA -keysize 4096 -validity 11000 -dname "CN=Your name"

Keep the keystore and its password out of the repository and backed up. Android installs an update only if it is
signed with the same key, so losing the key means users must uninstall (losing their accounts and game files) to
move to a new one.

APKs built by someone else, with their own key, are a different app as far as Android is concerned: they cannot
update the official TTR Mobile, and the official one cannot update them.

## Checking a download

The official releases at toontown.help list the signing certificate's SHA-256. To check an APK:

    java -jar <build-tools>/lib/apksigner.jar verify --print-certs TTR-Mobile-<version>.apk

## Package name in native code

Nothing native may hard-code `/data/user/0/<package>`. Paths come from the app at run time (for example the
Vulkan stack's libandroid-shmem reads `TMPDIR`, which the app sets to its own `files/wine-tmp`).
