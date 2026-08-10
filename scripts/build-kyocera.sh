#!/usr/bin/env bash
#
# Full build for BrightMusic Kyocera:
# 1. Cross-compile Rust core for armeabi-v7a
# 2. Binary-patch statx syscall (397) → fstatat64 (295)
# 3. Generate UniFFI Kotlin bindings
# 4. Build debug APK
# 5. Install on device
#
set -e

source "$HOME/.cargo/env"
export ANDROID_NDK_HOME=${ANDROID_NDK_HOME:-/opt/homebrew/share/android-commandlinetools/ndk/27.1.12297006}
cd "$(dirname "$0")/.."

echo "==> Building Rust core (armeabi-v7a)..."
cargo ndk -t armv7-linux-androideabi -o app/src/main/jniLibs --platform 21 \
    build --release --no-default-features --features audiotrack-sink

echo "==> Patching statx syscall (397 → 295 fstatat64)..."
python3 -c "
import struct
path = 'app/src/main/jniLibs/armeabi-v7a/libspotify_core.so'
with open(path, 'rb') as f:
    data = bytearray(f.read())

# Find and patch movw r0, #397 → movw r0, #295
# Thumb-2 movw: 0xf240 | (imm3<<12 | Rd<<8 | imm8)
# 397 = 0x018d: imm3=0, imm8=0x8d → second halfword 0x008d
# 295 = 0x0127: imm3=1, imm8=0x27 → second halfword 0x1027
patched = False
for i in range(len(data) - 4):
    h1 = struct.unpack_from('<H', data, i)[0]
    h2 = struct.unpack_from('<H', data, i+2)[0]
    if h1 == 0xf240 and (h2 & 0x8FFF) == 0x008d:
        rd = (h2 >> 8) & 0xf
        if rd == 0:
            data[i+2] = 0x27
            data[i+3] = 0x10
            print(f'  Patched movw r0, #397 → #295 at 0x{i:06x}')
            patched = True

# Patch 32-bit literal 397 → 295
target397 = struct.pack('<I', 397)
target295 = struct.pack('<I', 295)
lcount = 0
for i in range(len(data) - 4):
    if data[i:i+4] == target397:
        data[i:i+4] = target295
        lcount += 1

with open(path, 'wb') as f:
    f.write(data)
print(f'  {\"1 instruction\" if patched else \"0 instructions\"}, {lcount} literals patched')
"

echo "==> Building APK..."
./gradlew assembleDebug

echo "==> Installing..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "==> Done!"
echo "  APK: app/build/outputs/apk/debug/app-debug.apk"
echo "  Install: adb shell am start -n com.lightphone.spotify/.MainActivity"
