#!/usr/bin/env bash
# Build Zen and make it the home screen on a Google TV / Android TV (or an emulator).
#
#   ./install-tv.sh                      the only connected device
#   ./install-tv.sh 192.168.1.40         a TV on your network (Settings → System → Developer options → network debugging)
#   ./install-tv.sh emulator-5554        a specific emulator
#   ./install-tv.sh --yes …              don't ask before turning off the stock launcher
#   ./install-tv.sh --restore …          give Home back to the stock launcher (Zen stays installed)
#
# Most TVs ship a launcher whose Home filter outranks any app you choose, so after installing, this
# script offers to turn that launcher off for your user. Nothing is uninstalled, and --restore undoes it.
set -euo pipefail
cd "$(dirname "$0")"

APP_ID=io.github.mattdillioncodes.zen   # must match applicationId in app/build.gradle.kts
STOCK_LAUNCHERS=(com.google.android.apps.tv.launcherx com.google.android.tvlauncher)
assume_yes=false
restore=false
target=""
for arg in "$@"; do
  case "$arg" in
    --yes|-y) assume_yes=true ;;
    --restore) restore=true ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) target="$arg" ;;
  esac
done

# A bare IP (optionally :port) means a TV on the network.
if [[ "$target" =~ ^[0-9]+(\.[0-9]+){3}(:[0-9]+)?$ ]]; then
  [[ "$target" == *:* ]] || target="$target:5555"
  adb connect "$target" >/dev/null
fi
tv() { adb ${target:+-s "$target"} "$@"; }

installed_stock() {
  for pkg in "${STOCK_LAUNCHERS[@]}"; do
    if tv shell pm list packages "$pkg" | grep -qx "package:$pkg"; then echo "$pkg"; fi
  done
}

home_activity() {
  tv shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1 | tr -d '\r'
}

if $restore; then
  for pkg in $(installed_stock); do
    tv shell pm enable "$pkg" >/dev/null && echo "Turned $pkg back on."
    tv shell cmd role add-role-holder android.app.role.HOME "$pkg" 0 >/dev/null 2>&1 || true  # best effort; enabling is what matters
  done
  tv shell input keyevent KEYCODE_HOME
  echo "Home now opens: $(home_activity)"
  exit 0
fi

./gradlew --quiet assembleRelease
APK=$(ls -t app/build*/outputs/apk/release/app-release.apk | head -n 1)   # build/ or build.nosync/
tv install -r "$APK"
tv shell cmd role add-role-holder android.app.role.HOME "$APP_ID" 0

if [[ "$(home_activity)" != "$APP_ID"/* ]]; then
  echo "Your TV's own launcher ($(home_activity)) still takes priority over Zen."
  answer=n
  if $assume_yes; then answer=y
  else read -r -p "Turn it off so Home opens Zen? Undo any time with --restore. [y/N] " answer
  fi
  if [[ "$answer" =~ ^[Yy]$ ]]; then
    for pkg in $(installed_stock); do tv shell pm disable-user --user 0 "$pkg" >/dev/null && echo "Turned off $pkg."; done
  else
    echo "Left it alone. Zen is installed; open it from the Apps row."
  fi
fi

tv shell input keyevent KEYCODE_HOME
echo "Home now opens: $(home_activity)"
echo "To add your own wallpapers: press → on the remote and scan the QR code with your phone."
