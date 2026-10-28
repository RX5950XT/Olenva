#!/usr/bin/env bash
# Rebuild app/src/main/jniLibs/arm64-v8a/libopencv_java4.so: OpenCV with only the modules the app calls
# (core, imgproc, dnn, java), same version and options as the official org.opencv:opencv AAR otherwise.
# The AAR still supplies the Java classes, libc++_shared.so and the x86_64 library.
#
# Needs: Android SDK with NDK 27 and CMake 3.22.1, JDK (Android Studio's jbr), Python 3, git, curl.
# Usage: tools/build_opencv_slim.sh <work-dir>      (takes ~10 min; when bumping OpenCV, change VERSION and the AAR together)
set -euo pipefail
VERSION=4.13.0
WORK=$(mkdir -p "$1" && cd "$1" && pwd)
APP=$(cd "$(dirname "$0")/.." && pwd)
SDK=${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}
NDK=$(ls -d "$SDK"/ndk/27.* | tail -1)
CM="$SDK/cmake/3.22.1/bin"
: "${JAVA_HOME:?point JAVA_HOME at a JDK, e.g. the jbr folder of Android Studio}"

[ -d "$WORK/src" ] || git -c core.longpaths=true clone --depth 1 -b $VERSION https://github.com/opencv/opencv.git "$WORK/src"
# CMake's own downloader fails TLS against gitlab.arm.com on Windows; fetch KleidiCV (ARM image-processing HAL) with curl.
mkdir -p "$WORK/dl"
[ -f "$WORK/dl/kleidicv-0.7.0.tar.gz" ] || curl -sSfL -o "$WORK/dl/kleidicv-0.7.0.tar.gz" https://gitlab.arm.com/kleidi/kleidicv/-/archive/0.7.0/kleidicv-0.7.0.tar.gz
DL="file:///$(cd "$WORK/dl" && pwd -W 2>/dev/null || pwd)/"

"$CM/cmake" -S "$WORK/src" -B "$WORK/build-arm64" -G Ninja -DCMAKE_MAKE_PROGRAM="$CM/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_NDK="$NDK" -DANDROID_ABI=arm64-v8a \
  -DANDROID_NATIVE_API_LEVEL=21 -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_LIST=core,imgproc,dnn,java,java_bindings_generator \
  -DBUILD_SHARED_LIBS=OFF -DBUILD_FAT_JAVA_LIB=ON -DANDROID_PROJECTS_BUILD_TYPE=GRADLE -DBUILD_ANDROID_PROJECTS=OFF \
  -DPYTHON_DEFAULT_EXECUTABLE="$(command -v python || command -v python3)" -DOPENCV_KLEIDICV_URL="$DL" \
  -DWITH_OPENCL=OFF -DWITH_TBB=ON -DWITH_IPP=OFF -DWITH_ANDROID_MEDIANDK=OFF -DBUILD_KOTLIN_EXTENSIONS=OFF \
  -DBUILD_ANDROID_EXAMPLES=OFF -DBUILD_EXAMPLES=OFF -DBUILD_TESTS=OFF -DBUILD_PERF_TESTS=OFF -DBUILD_DOCS=OFF
"$CM/cmake" --build "$WORK/build-arm64" --target opencv_java
mkdir -p "$APP/app/src/main/jniLibs/arm64-v8a"
cp "$(find "$WORK/build-arm64" -name libopencv_java4.so | head -1)" "$APP/app/src/main/jniLibs/arm64-v8a/"
ls -l "$APP/app/src/main/jniLibs/arm64-v8a/libopencv_java4.so"
