#!/usr/bin/env bash
# Compila una versión mínima de FFmpeg (solo decodificadores XMA1/XMA2 y MP3) para Android.
# Uso: ANDROID_NDK_HOME=/ruta/al/ndk scripts/build-ffmpeg.sh
# Resultado: ffmpeg-android/<abi>/{include,lib}
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-7.1.1}"
API=24
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$HERE/ffmpeg-android"
WORK="${WORK_DIR:-$HERE/build/ffmpeg-src}"
NDK="${ANDROID_NDK_HOME:?Falta ANDROID_NDK_HOME}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

mkdir -p "$WORK"
cd "$WORK"
if [ ! -d "ffmpeg-$FFMPEG_VERSION" ]; then
  curl -fsSL "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" -o ffmpeg.tar.xz
  tar xJf ffmpeg.tar.xz
fi
cd "ffmpeg-$FFMPEG_VERSION"

build_abi() {
  local abi="$1" arch="$2" cc_prefix="$3"
  echo "=== FFmpeg $FFMPEG_VERSION para $abi ==="
  make distclean >/dev/null 2>&1 || true
  ./configure \
    --prefix="$OUT/$abi" \
    --enable-cross-compile \
    --target-os=android \
    --arch="$arch" \
    --cc="$TOOLCHAIN/bin/${cc_prefix}${API}-clang" \
    --cxx="$TOOLCHAIN/bin/${cc_prefix}${API}-clang++" \
    --ar="$TOOLCHAIN/bin/llvm-ar" \
    --nm="$TOOLCHAIN/bin/llvm-nm" \
    --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
    --strip="$TOOLCHAIN/bin/llvm-strip" \
    --sysroot="$TOOLCHAIN/sysroot" \
    --extra-cflags="-O2 -fPIC" \
    --enable-pic \
    --disable-asm \
    --enable-static --disable-shared \
    --disable-everything --disable-autodetect \
    --disable-programs --disable-doc \
    --disable-avdevice --disable-avformat --disable-avfilter \
    --disable-swscale --disable-swresample --disable-postproc --disable-network \
    --enable-decoder=xma1,xma2,mp3,mp3float \
    --enable-parser=mpegaudio
  make -j"$(nproc)"
  make install
}

build_abi arm64-v8a aarch64 aarch64-linux-android
build_abi armeabi-v7a arm armv7a-linux-androideabi
build_abi x86_64 x86_64 x86_64-linux-android
echo "FFmpeg listo en $OUT"
