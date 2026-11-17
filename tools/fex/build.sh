#!/usr/bin/env bash
# Build TTR Mobile's FEX x86-64 emulator DLL for Wine (ARM64EC): FEX-2610 plus tools/fex/msvc-istream-ignore.patch.
# Run inside WSL Ubuntu 24.04:  bash tools/fex/build.sh
# Needs cmake, ninja, git, curl. Output: build/fex/libarm64ecfex.dll (stripped), packaged by
# tools/build_app.py. The patch is ours and stays local: FEX takes no AI-written contributions.
set -euo pipefail

REPO=$(cd "$(dirname "$0")/../.." && pwd)
WORK=${WORK:-$HOME/fexbuild}
FEX_TAG=FEX-2610
FEX_COMMIT=14c92681f4d62cf84d901460e0358de09c8847a7
MINGW=llvm-mingw-20261104-ucrt-ubuntu-22.04-x86_64
MINGW_SHA256=919380403de87dca3c581c2053230cd298af5e891286628ba0613b7fed370926

mkdir -p "$WORK"; cd "$WORK"
if [ ! -d llvm-mingw ]; then
  curl -sL -o mingw.tar.xz "https://github.com/mstorsjo/llvm-mingw/releases/download/20261104/$MINGW.tar.xz"
  echo "$MINGW_SHA256  mingw.tar.xz" | sha256sum -c -
  tar xf mingw.tar.xz && mv "$MINGW" llvm-mingw
fi
export PATH=$WORK/llvm-mingw/bin:$PATH
if [ ! -d FEX ]; then
  git clone --depth 1 --branch "$FEX_TAG" https://github.com/FEX-Emu/FEX.git
  git -C FEX submodule update --init --depth 1
fi
cd FEX
[ "$(git rev-parse HEAD)" = "$FEX_COMMIT" ] || { echo "FEX is not at $FEX_TAG ($FEX_COMMIT)"; exit 1; }
git reset -q --hard && git clean -fdq FEXCore/Source   # start from the tag, then apply our patch
git apply "$REPO/tools/fex/msvc-istream-ignore.patch"
# The same options as FEX's own Wine DLL build (.github/workflows/wine_build/action.yml).
cmake -S . -B build_arm64ec -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE=Data/CMake/toolchain_mingw.cmake \
  -DMINGW_TRIPLE=arm64ec-w64-mingw32 -DCMAKE_INSTALL_LIBDIR=/usr/lib/wine/aarch64-windows -G Ninja \
  -DENABLE_LTO=False -DENABLE_ASSERTIONS=False -DENABLE_JEMALLOC_GLIBC_ALLOC=False \
  -DBUILD_TESTING=False -DCMAKE_INSTALL_PREFIX=/usr -DTUNE_ARCH=generic -DTUNE_CPU=none -DRANGES_NATIVE=OFF > /dev/null
cmake --build build_arm64ec --target arm64ecfex
mkdir -p "$REPO/build/fex"
llvm-strip -o "$REPO/build/fex/libarm64ecfex.dll" build_arm64ec/Bin/libarm64ecfex.dll
sha256sum "$REPO/build/fex/libarm64ecfex.dll"
