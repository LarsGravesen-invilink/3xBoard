#!/usr/bin/env bash
set -euo pipefail
root="$(dirname "$(dirname "$(dirname "$(realpath "$0")")")")"
tooling="$root/tools/android-build"
build_only=false
serial="${ANDROID_SERIAL:-}"
gradle_args=()
while (($#)); do
    case "$1" in
        --build-only) build_only=true; shift ;;
        --offline) gradle_args+=(--offline); shift ;;
        --serial)
            [[ $# -ge 2 && -n "$2" ]] || { printf '%s\n' 'Missing --serial value' >&2; exit 2; }
            serial="$2"; shift 2 ;;
        *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
    esac
done
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_HOME="$sdk"
[[ -d "$root/../3Xclient_apk" ]] || { printf '%s\n' 'Export directory ../3Xclient_apk is missing' >&2; exit 1; }
python3 "$tooling/import-gradle-cache.py"
bash "$root/gradlew" -p "$root" -I "$tooling/local-cache.init.gradle" --console=plain "${gradle_args[@]}" :app:assembleDebug :app:exportDebugApk
apk="$(python3 - "$root" <<'PY'
import json
from pathlib import Path
import sys
root = Path(sys.argv[1])
metadata = json.loads((root / "app/build/outputs/apk/debug/output-metadata.json").read_text())
print(root.parent / "3Xclient_apk" / f"3xBoard-{metadata['elements'][0]['versionName']}.apk")
PY
)"
[[ -f "$apk" ]] || { printf 'Export missing: %s\n' "$apk" >&2; exit 1; }
cmp -s "$root/app/build/outputs/apk/debug/app-debug.apk" "$apk" || { printf '%s\n' 'Export differs from assembled APK' >&2; exit 1; }
sha256sum "$apk"
$build_only && exit 0
adb="$sdk/platform-tools/adb"
[[ -x "$adb" ]] || { printf 'adb not found: %s\n' "$adb" >&2; exit 1; }
choose_target() {
    local row listing
    listing="$("$adb" devices)"
    targets=()
    while IFS= read -r row; do
        [[ -n "$row" && "$row" != 'List of devices attached'* && "$row" != '*'* ]] && targets+=("$row")
    done <<< "$listing"
    return 0
}
if [[ -z "$serial" ]]; then
    choose_target
    if ((${#targets[@]} == 0)); then
        [[ -x "$sdk/emulator/emulator" ]] || { printf '%s\n' 'No target and emulator is unavailable' >&2; exit 1; }
        avd=Medium_Phone_API_37.0
        avds="$("$sdk/emulator/emulator" -list-avds)"
        [[ $'\n'"$avds"$'\n' == *$'\n'"$avd"$'\n'* ]] || { printf 'AVD missing: %s\n' "$avd" >&2; exit 1; }
        [[ -n "${DISPLAY:-}" ]] || { printf '%s\n' 'GUI DISPLAY required for emulator (XWayland is supported)' >&2; exit 1; }
        QT_QPA_PLATFORM=xcb nohup "$sdk/emulator/emulator" -avd "$avd" -no-boot-anim > "$tooling/emulator.log" 2>&1 < /dev/null &
        emulator_pid=$!
        for ((attempt=0; attempt<90; attempt++)); do
            sleep 2
            choose_target
            if ((${#targets[@]} > 1)); then
                break
            fi
            if ((${#targets[@]} == 1)); then
                read -r candidate state _ <<< "${targets[0]}"
                [[ "$state" == device ]] && break
            fi
            kill -0 "$emulator_pid" 2>/dev/null || { printf 'Emulator failed; see %s/emulator.log\n' "$tooling" >&2; exit 1; }
        done
    fi
    ((${#targets[@]} == 1)) || { printf '%s\n' 'No unambiguous adb target; use --serial SERIAL' >&2; exit 1; }
    read -r serial state _ <<< "${targets[0]}"
    [[ "$state" == device ]] || { printf 'Target %s is %s; refusing install\n' "$serial" "$state" >&2; exit 1; }
fi
[[ "$("$adb" -s "$serial" get-state)" == device ]] || { printf 'Target not ready: %s\n' "$serial" >&2; exit 1; }
booted=false
for ((attempt=0; attempt<90; attempt++)); do
    if [[ "$("$adb" -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then
        booted=true
        break
    fi
    sleep 2
done
$booted || { printf 'Boot timeout: %s\n' "$serial" >&2; exit 1; }
printf 'Installing on %s without clearing data\n' "$serial"
if ! install_output="$("$adb" -s "$serial" install -r "$apk" 2>&1)"; then
    printf '%s\n' "$install_output" >&2
    printf '%s\n' 'Install stopped. No uninstall or data reset will be attempted.' >&2
    exit 1
fi
printf '%s\n' "$install_output"
launch_output="$("$adb" -s "$serial" shell am start -W -n app.invilink.xboard/.MainActivity)"
printf '%s\n' "$launch_output"
[[ "$launch_output" == *'Status: ok'* ]] || exit 1
sleep 3
"$adb" -s "$serial" shell pidof app.invilink.xboard
