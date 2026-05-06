#!/bin/bash
# Build Midnight Kicks — full pipeline
# Rebuilds Rust FFI → SDK AARs → Kicks APK → installs on device
set -e

KUIRA_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KICKS_ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "═══ Midnight Kicks Build ═══"
echo "  Kuira: $KUIRA_ROOT"
echo "  Kicks: $KICKS_ROOT"
echo ""

# Step 1: Cross-compile Rust FFI for arm64
echo "── Step 1: Rust FFI (arm64) ──"
cd "$KUIRA_ROOT"
if [ -f kuira-crypto-ffi/build-android.sh ]; then
    cd kuira-crypto-ffi && ./build-android.sh && cd "$KUIRA_ROOT"
else
    echo "ERROR: kuira-crypto-ffi/build-android.sh not found"
    exit 1
fi

# Step 2: Build SDK AARs
echo ""
echo "── Step 2: SDK AARs ──"
cd "$KUIRA_ROOT"
./gradlew :core:crypto:clean :core:compact-engine:clean --quiet
./gradlew \
    :core:crypto:assembleDebug \
    :core:compact-engine:assembleDebug \
    :sdk:midnight-sdk:assembleDebug \
    :core:identity:assembleDebug \
    :core:network:assembleDebug \
    :core:indexer:assembleDebug \
    :core:ledger:assembleDebug \
    :core:auth:assembleDebug \
    --quiet

# Step 3: Copy AARs to Kicks
echo ""
echo "── Step 3: Copy AARs ──"
mkdir -p "$KICKS_ROOT/libs"
for module in sdk/midnight-sdk core/compact-engine core/crypto core/identity core/network core/indexer core/ledger core/auth; do
    name=$(basename "$module")
    cp "$KUIRA_ROOT/$module/build/outputs/aar/${name}-debug.aar" "$KICKS_ROOT/libs/"
    echo "  ✓ $name"
done

# Step 4: Build Kicks APK
echo ""
echo "── Step 4: Kicks APK ──"
cd "$KICKS_ROOT"
./gradlew :app:clean :app:assembleDebug --quiet

APK="$KICKS_ROOT/app/build/outputs/apk/debug/app-debug.apk"
echo "  ✓ $(du -h "$APK" | cut -f1) APK"

# Step 5: Install on device (if connected)
echo ""
echo "── Step 5: Install ──"
if adb devices 2>/dev/null | grep -q "device$"; then
    adb install -r "$APK"
    echo "  ✓ Installed"
else
    echo "  ⚠ No device connected — APK at $APK"
fi

echo ""
echo "═══ Done ═══"
