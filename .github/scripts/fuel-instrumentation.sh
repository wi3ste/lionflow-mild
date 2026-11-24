#!/usr/bin/env bash
set -u

result=0
gradle :app:connectedDebugAndroidTest --no-daemon || result=$?
mkdir -p fuel-review-shots
for shot in fuel-log-light fuel-statistics-light fuel-favorites-dark; do
    if ! adb pull "/sdcard/Download/LionFlowFuelReview/$shot.png" "fuel-review-shots/$shot.png" ||
        ! file "fuel-review-shots/$shot.png" | grep -q 'PNG image'; then
        rm -f "fuel-review-shots/$shot.png"
    fi
done
if [ "$result" -ne 0 ]; then
    adb logcat -d -s FuelMap FuelMapTest Mbgl MapLibre AndroidRuntime
    find app/build/outputs/androidTest-results/connected/debug -name 'TEST-*.xml' -exec cat {} +
fi
exit "$result"
