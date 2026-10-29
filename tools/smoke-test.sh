#!/usr/bin/env bash
# End-to-end check of the wallpaper upload server, including the abuse cases it must survive.
# Needs a DEBUG build of Zen on the device (debug builds log the upload link) and adb + curl + sips (macOS).
#
#   tools/smoke-test.sh [device]       e.g. emulator-5554
#
# It opens the control panel with the remote, runs the checks, removes the photo it uploaded, and leaves
# the panel closed. Exits non-zero if anything fails.
set -uo pipefail
cd "$(dirname "$0")/.."
device="${1:-}"
tv() { adb ${device:+-s "$device"} "$@"; }
LOCAL_PORT=18089
fails=0

check() { # expected actual description
  if [[ "$2" == "$1" ]]; then echo "  ok    $3 ($2)"; else echo "  FAIL  $3: expected $1, got $2"; fails=$((fails + 1)); fi
}
status() { curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$@"; }
raw() { # send raw bytes on a fresh connection, print the HTTP status code (or "closed")
  local reply
  reply=$( { printf "$1"; sleep "${2:-1}"; } | nc -w 15 localhost "$LOCAL_PORT" 2>/dev/null | head -c 12)
  [[ -n "$reply" ]] && echo "${reply:9:3}" || echo "closed"
}

echo "Opening the control panel…"
tv logcat -c
tv shell input keyevent KEYCODE_HOME; sleep 2
tv shell input keyevent KEYCODE_DPAD_RIGHT; sleep 2
url=$(tv logcat -d -s Zen | grep -o 'http://[^ ]*' | tail -n 1)
if [[ -z "$url" ]]; then echo "No upload link in logcat: is a debug build of Zen on screen?"; exit 1; fi
token=${url##*t=}
port=$(sed -E 's#http://[^:]+:([0-9]+)/.*#\1#' <<<"$url")
tv forward "tcp:$LOCAL_PORT" "tcp:$port" >/dev/null
base="http://localhost:$LOCAL_PORT"
pid_before=$(tv shell pidof com.zen.tv | tr -d '\r')
work=$(mktemp -d)
sips -s format png app/src/main/assets/wallpapers/01-enso.jpg --out "$work/photo.png" >/dev/null
sips -z 5000 1000 app/src/main/assets/wallpapers/01-enso.jpg --out "$work/tall.jpg" >/dev/null

echo "Basics"
check 403 "$(status "$base/?t=wrong")" "wrong code is refused"
check 403 "$(status "$base/")" "missing code is refused"
check 200 "$(status "$base/?t=$token")" "upload page loads with the code"
check 404 "$(status "$base/nope?t=$token")" "unknown path"

echo "Uploads"
check 411 "$(raw "POST /upload?t=$token HTTP/1.1\r\nHost: x\r\n\r\n")" "missing Content-Length"
check 413 "$(raw "POST /upload?t=$token HTTP/1.1\r\nContent-Length: 99999999\r\n\r\n")" "oversized upload refused before reading it"
check 415 "$(status --data-binary 'hello' -H 'Content-Type: image/jpeg' "$base/upload?t=$token")" "text pretending to be a photo"
check 415 "$(status --data-binary @"$work/photo.png" -H 'Content-Type: image/jpeg' "$base/upload?t=$token")" "PNG instead of JPEG"
check 415 "$(status --data-binary @"$work/tall.jpg" -H 'Content-Type: image/jpeg' "$base/upload?t=$token")" "1000x5000 photo (crash-loop guard)"
check 200 "$(status --data-binary @app/src/main/assets/wallpapers/02-mist.jpg -H 'Content-Type: image/jpeg' "$base/upload?t=$token")" "real photo is accepted"

echo "Abuse"
flood="GET /?t=wrong HTTP/1.1\r\n"; for i in $(seq 1 2000); do flood+="X-$i: $(printf 'a%.0s' {1..200})\r\n"; done
check 403 "$(raw "$flood\r\n")" "header flood without the code is refused at the first line"
many="GET /?t=$token HTTP/1.1\r\n"; for i in $(seq 1 80); do many+="X-$i: a\r\n"; done
check 431 "$(raw "$many\r\n")" "too many headers with the code"
for i in 1 2 3 4 5 6; do ( { sleep 14; } | nc localhost "$LOCAL_PORT" >/dev/null 2>&1 & ); done
sleep 12   # idle connections hold the slots until the 10 s header limit drops them
check 200 "$(status "$base/?t=$token")" "server recovers after idle connections time out"
check "$pid_before" "$(tv shell pidof com.zen.tv | tr -d '\r')" "Zen never crashed (same process)"

echo "Cleanup"
tv shell input keyevent KEYCODE_DPAD_LEFT
newest=$(tv shell run-as com.zen.tv ls -t files/wallpapers 2>/dev/null | tr -d '\r' | grep '^wp_' | head -n 1)
[[ -n "$newest" ]] && tv shell run-as com.zen.tv rm "files/wallpapers/$newest" && echo "  removed test upload $newest"
tv forward --remove "tcp:$LOCAL_PORT" >/dev/null 2>&1
rm -r "$work"

if (( fails )); then echo "$fails check(s) failed"; exit 1; else echo "All checks passed"; fi
