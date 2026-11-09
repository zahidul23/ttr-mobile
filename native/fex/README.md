# FEX for Wine (bundled)

`fex-emu-wine_2610-1~n_arm64.deb` is FEX's official x86/x86-64 emulator for Wine on ARM64, unchanged from
FEX's Ubuntu PPA (https://launchpad.net/~fex-emu/+archive/ubuntu/fex). FEX is MIT licensed (`LICENSE`, from
the `FEX-2610` tag of https://github.com/FEX-Emu/FEX).

It ships inside the APK instead of being downloaded on first start: the PPA only keeps its newest build, and
Launchpad's address for older builds stops working about a month after they are replaced.

- SHA-256: `edd6a8266f82199b1323549d64d75295b44773330dc61a44e3e5d9529bd100ba`
- Size: 1827696 bytes

To update FEX, replace the file and change `FEX_DEB` and `FEX_SHA256` in `tools/build_app.py` and `FEX_SHA256`
and `FEX_SIZE` in `app/src/org/ttrmobile/TtrWineRuntime.java`. A new hash also changes the
runtime version, so existing installs set up the runtime again.
