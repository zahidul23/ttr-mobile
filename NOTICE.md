# Notices

TTR Mobile is free software under the GNU General Public License version 3 (see `LICENSE`), because it
includes code from Termux:X11, which is GPLv3. Anyone who shares an APK built from this repository must
also offer its corresponding source.

TTR Mobile is an unofficial project. It is not made, endorsed or supported by the Toontown Rewritten team or by
Disney. Toontown Rewritten, its game, artwork, logo and name belong to their owners; none of that is covered by
this license. The only TTR artwork in this repository is in the README screenshots (`docs/screenshots/`). The build
extracts the logo and launcher buttons from TTR's official installer, and the app downloads the game from TTR's
servers.

The app icon and the logo on the launch screen are modified from the original Toontown Rewritten logo: the build
(`tools/make_icon.py`) places TTR's logo on a blue background and adds a red "UNOFFICIAL" stamp across it, so the
app is not mistaken for an official TTR release.

## Built into the app

| Component | License | Use |
|---|---|---|
| [Termux:X11](https://github.com/termux/termux-x11) (X server, `LorieView`) | GPL-3.0 | the display the game draws to |
| X.Org libraries: X11, xcb, Xau, Xdmcp, Xext, Xrender, Xfixes, Xrandr, Xcursor; xkeyboard-config | MIT/X11 | X client side and keyboard maps |
| [GL4ES](https://github.com/ptitSeb/gl4es) | MIT | compatibility graphics (OpenGL on GLES) |
| [Mesa](https://mesa3d.org) 25.1.5 (zink, Turnip), [Khronos Vulkan loader](https://github.com/KhronosGroup/Vulkan-Loader), libdrm, libxshmfence, Xxf86vm | MIT and others (texts in the package's `licenses/`) | faster graphics (OpenGL on Vulkan) |
| [libandroid-shmem](https://github.com/termux/libandroid-shmem) | BSD-3-Clause | shared memory for the Vulkan path |
| bzip2 1.0.8 | bzip2 license | game file downloads |
| [zstd](https://github.com/facebook/zstd) 1.5.6 | BSD-3-Clause | unpacking the Windows runtime |
| [XZ Embedded](https://github.com/tukaani-project/xz-embedded) | 0BSD | unpacking the Windows runtime |

Exact revisions and checksums are in `tools/sources.json` and `tools/vulkan_stack/build.sh`. The APK carries the
license texts under `assets/licenses/`.

## Downloaded by the app on first start

The Windows runtime is not part of the APK. The app downloads pinned, checksummed builds of Wine (GameNative's
Proton build), FEX-Emu and GameNative's Vulkan wrapper from their public sources (see
`app/src/org/ttrmobile/TtrWineRuntime.java` and `TtrGraphics.java`); each keeps its own license.
