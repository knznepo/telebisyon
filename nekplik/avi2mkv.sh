#!/bin/bash
# Turn every .avi under a folder into an .mkv the TV can play, without re-encoding.
#
# Android TVs have no AVI reader, but they do decode what's inside most AVIs (XviD/DivX video, MP3 or
# AC3 sound). So this copies the same video and sound into an MKV: no quality lost, a minute or two
# a film, limited by the drive rather than the computer.
#
#   ./avi2mkv.sh /Volumes/Movies             convert, and move each AVI to "AVI originals" on that drive
#   ./avi2mkv.sh /Volumes/Movies --delete    convert, and delete each AVI once its MKV checks out
#
# Each MKV goes beside its AVI, with the same name, so .srt subtitles still match. An AVI is only moved
# or deleted after its MKV opens and runs as long as it did. Samples and ads (sample.avi, RARBG.com.avi)
# are left alone. Run it again any time: AVIs already done are skipped.
#
# Needs ffmpeg: on a Mac, "brew install ffmpeg"; on Windows, https://ffmpeg.org.

set -u
root="${1:-}"
mode="${2:-move}"
if [ -z "$root" ] || [ ! -d "$root" ]; then
    echo "Usage: $0 <folder> [--delete]" >&2
    exit 1
fi
command -v ffmpeg >/dev/null && command -v ffprobe >/dev/null || { echo "Needs ffmpeg: brew install ffmpeg" >&2; exit 1; }
root="${root%/}"
keep="$root/AVI originals"

duration() { ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$1" 2>/dev/null | cut -d. -f1; }
vcodec() { ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of default=nw=1:nk=1 "$1" 2>/dev/null; }

done_=0 skipped=0 failed=0
list=$(mktemp)
find "$root" -type f -iname '*.avi' ! -name '._*' ! -path "$keep/*" ! -path '*RECYCLE*' -print0 > "$list"
total=$(tr -cd '\0' < "$list" | wc -c | tr -d ' ')
n=0
while IFS= read -r -d '' avi; do
    n=$((n + 1))
    name=$(basename "$avi")
    lower=$(echo "$name" | tr '[:upper:]' '[:lower:]')
    size=$(stat -f %z "$avi" 2>/dev/null || stat -c %s "$avi")
    if [[ "$lower" == *sample* || "$lower" == rarbg* || "$size" -lt 50000000 ]]; then
        echo "[$n/$total] skip (sample or ad): $avi"; skipped=$((skipped + 1)); continue
    fi
    dir=$(dirname "$avi")
    mkv="$dir/${name%.*}.mkv"
    part="$dir/.${name%.*}.mkv.part"
    want=$(duration "$avi")
    if [ -f "$mkv" ] && [ -n "$want" ] && [ "$(duration "$mkv")" = "$want" ]; then
        echo "[$n/$total] already done: $mkv"
    else
        echo "[$n/$total] $avi"
        bsf=()
        [ "$(vcodec "$avi")" = "mpeg4" ] && bsf=(-bsf:v mpeg4_unpack_bframes)   # XviD's "packed" frames
        rm -f "$part"
        if ! ffmpeg -nostdin -v error -y -fflags +genpts -i "$avi" -map 0:v:0 -map '0:a?' -c copy "${bsf[@]}" -f matroska "$part" \
            && ! { rm -f "$part"; ffmpeg -nostdin -v error -y -fflags +genpts+igndts -i "$avi" -map 0:v:0 -map '0:a?' -c copy "${bsf[@]}" -f matroska "$part"; }; then
            echo "   FAILED: ffmpeg couldn't copy it, the AVI is untouched"; rm -f "$part"; failed=$((failed + 1)); continue
        fi
        got=$(duration "$part")
        if [ -z "$got" ] || [ -z "$want" ] || [ $((got > want ? got - want : want - got)) -gt $((want / 100 + 2)) ]; then
            echo "   FAILED: the MKV runs ${got:-?}s, the AVI ${want:-?}s; the AVI is untouched"; rm -f "$part"; failed=$((failed + 1)); continue
        fi
        mv -f "$part" "$mkv"
    fi
    if [ "$mode" = "--delete" ]; then
        rm -f "$avi"
    else
        rel="${avi#"$root"/}"
        mkdir -p "$keep/$(dirname "$rel")" && mv "$avi" "$keep/$rel"
    fi
    done_=$((done_ + 1))
done < "$list"
rm -f "$list"

echo
echo "Converted $done_, skipped $skipped samples or ads, $failed failed."
[ "$mode" != "--delete" ] && [ -d "$keep" ] && echo "The AVIs are in \"$keep\"; once the films play on the TV, you can delete that folder."
[ "$failed" -eq 0 ]
