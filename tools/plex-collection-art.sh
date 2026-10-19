#!/usr/bin/env bash
# plex-collection-art.sh — push Youtarr channel artwork into Plex collections.
#
# Youtarr saves poster.jpg (channel art) and backdrop.jpg (channel banner) in
# each channel folder, but Plex's local-movie agent only reads assets inside
# the per-VIDEO leaf folders — the channel-level jpgs are ignored, and Plex
# gives every collection an auto-generated composite collage instead.
# This script uploads the two channel files as the collection's POSTER and
# BACKGROUND through the Plex API (the same mechanism the Web UI uses), so
# they stick and are served as /photo/ artwork to any client — including
# third-party apps.
#
# Run ON the Plex server (Ubuntu), where the library paths below exist.
#
#   ./plex-collection-art.sh --dry-run   # show matches/plans, change nothing
#   ./plex-collection-art.sh             # upload (skips collections that
#                                        # already have uploaded art)
#   ./plex-collection-art.sh --force     # re-upload everything
#
# Config (env vars, all optional):
#   PLEX_URL   default http://127.0.0.1:32400
#   LIB_DIR    REQUIRED — the library root folder (server-side path)
#   SECTION    Plex library id; default: auto-detected from LIB_DIR
#   PLEX_TOKEN token string; else read from ~/.owndemand-scan-token or
#              ~/.plex-token. Needs owner (or edit-permitted) access.
#
# Note: uploaded art replaces the collage for that collection and is
# auto-selected; it is then served as /library/metadata/{rk}/thumb|art, which
# is how this script tells "uploaded" from "still the auto-collage". To
# restore the collage afterwards, re-pick it in the Plex Web UI (collection →
# Edit → Posters). Backgrounds are supported on current Plex builds (verified
# 2026-10-18); the script reports per-collection HTTP codes if a build refuses.

set -u

PLEX_URL="${PLEX_URL:-http://127.0.0.1:32400}"
LIB_DIR="${LIB_DIR:-}"
DRY=0; FORCE=0
[ "${1:-}" = "--dry-run" ] && DRY=1
[ "${1:-}" = "--force" ] && FORCE=1

TOKEN="${PLEX_TOKEN:-}"
if [ -z "$TOKEN" ]; then
  for f in ~/.owndemand-scan-token ~/.plex-token; do
    [ -r "$f" ] && TOKEN=$(tr -d '[:space:]' < "$f") && break
  done
fi
[ -n "$TOKEN" ] || { echo "No token: set PLEX_TOKEN or create ~/.owndemand-scan-token" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl is required" >&2; exit 1; }

# curl with the token header read from stdin (-H @-), so the token never
# appears on the command line where other users could see it in `ps`.
pcurl() { printf 'X-Plex-Token: %s\n' "$TOKEN" | curl -H @- "$@"; }
[ -n "$LIB_DIR" ] || { echo "Set LIB_DIR to the library root folder (server-side path)." >&2; exit 1; }
[ -d "$LIB_DIR" ] || { echo "No library dir: $LIB_DIR" >&2; exit 1; }

xml_get() { # attr, line
  printf '%s' "$2" | sed -n "s/.* $1=\"\([^\"]*\)\".*/\1/p"
}
xml_unescape() {
  sed -e 's/&#39;/'"'"'/g' -e 's/&apos;/'"'"'/g' \
      -e 's/&#8217;/’/g' -e 's/&#8216;/‘/g' \
      -e 's/&#8220;/“/g' -e 's/&#8221;/”/g' \
      -e 's/&quot;/"/g' -e 's/&lt;/</g' -e 's/&gt;/>/g' -e 's/&amp;/\&/g'
}
# Matching key: lowercase, curly quotes -> straight (Plex escapes U+2019 as
# &#8217; while the folder name holds it raw, e.g. "Some Creator’s Workshop").
norm() {
  printf '%s' "${1,,}" | sed -e "s/’/'/g" -e "s/‘/'/g" -e 's/“/"/g' -e 's/”/"/g'
}

# --- library section id ------------------------------------------------------
if [ -z "${SECTION:-}" ]; then
  SECTION=$(pcurl -fsS "$PLEX_URL/library/sections" \
    | awk -v p="$LIB_DIR" '
        /<Directory/ { attrs = $0 }
        index($0, "path=\"" p "\"") {
          if (match(attrs, /key="[0-9]+"/))
            print substr(attrs, RSTART+5, RLENGTH-6)
        }')
  [ -n "$SECTION" ] || { echo "Could not auto-detect the section for $LIB_DIR; set SECTION=<id>" >&2; exit 1; }
fi

# --- collection map: lower(title) -> ratingKey / thumb -----------------------
declare -A COLL_ID COLL_THUMB
COLLS_XML=$(pcurl -fsS "$PLEX_URL/library/sections/$SECTION/collections") \
  || { echo "Could not list collections (section $SECTION)" >&2; exit 1; }
while IFS= read -r line; do
  title=$(xml_get title "$line" | xml_unescape)
  rk=$(xml_get ratingKey "$line")
  th=$(xml_get thumb "$line")
  [ -n "$title" ] && [ -n "$rk" ] || continue
  COLL_ID["$(norm "$title")"]="$rk"
  COLL_THUMB["$(norm "$title")"]="$th"
done < <(printf '%s' "$COLLS_XML" | tr '<' '\n' | grep '^Directory')

echo "Section $SECTION: ${#COLL_ID[@]} collections; scanning $LIB_DIR"

ok=0; up=0; sk=0; miss=0; fail=0
for d in "$LIB_DIR"/*/; do
  name=$(basename "$d")
  [ "$name" = "__playlists__" ] && continue
  poster="$d/poster.jpg"; backdrop="$d/backdrop.jpg"
  nk=$(norm "$name")
  rk="${COLL_ID[$nk]:-}"
  if [ -z "$rk" ]; then
    echo "  ✗ $name — no matching collection"; miss=$((miss+1)); continue
  fi
  have="${COLL_THUMB[$nk]:-}"
  uploaded="no"
  case "$have" in
    *"/library/metadata/"*) uploaded="yes";;   # uploaded art is served from /library/metadata/{rk}/thumb|art
  esac                                      # auto-collage = /library/collections/{rk}/composite

  if [ "$DRY" = 1 ]; then
    echo "  · $name → collection $rk: poster=$([ -f "$poster" ] && echo yes || echo NO)" \
         "backdrop=$([ -f "$backdrop" ] && echo yes || echo no)" \
         "existing=$([ "$uploaded" = yes ] && echo uploaded || echo collage)"
    continue
  fi

  line_ok=1
  if [ -f "$poster" ] && { [ "$FORCE" = 1 ] || [ "$uploaded" != yes ]; }; then
    # Proven flow (same as PlexAPI's Collection.uploadPoster): POST the raw
    # image bytes; PMS stores and auto-selects them. Content-Type suppressed
    # so the body is sent bare like PlexAPI does.
    code=$(pcurl -s -o /dev/null -w '%{http_code}' -X POST \
      -H 'Content-Type:' --data-binary @"$poster" \
      "$PLEX_URL/library/metadata/$rk/posters")
    case "$code" in
      2*) echo "  ✓ $name — poster uploaded"; line_ok=1;;
      *)  echo "  ✗ $name — poster upload failed (HTTP $code)"; line_ok=0; fail=$((fail+1));;
    esac
  elif [ "$uploaded" = yes ] && [ "$FORCE" != 1 ]; then
    echo "  – $name — poster already uploaded (skipped; --force to re-send)"
    sk=$((sk+1))
  fi

  # Background (optional; skipped with the poster when already uploaded).
  if [ -f "$backdrop" ] && [ "$line_ok" = 1 ] && { [ "$FORCE" = 1 ] || [ "$uploaded" != yes ]; }; then
    code=$(pcurl -s -o /dev/null -w '%{http_code}' -X POST \
      -H 'Content-Type:' --data-binary @"$backdrop" \
      "$PLEX_URL/library/metadata/$rk/arts")
    case "$code" in
      2*) echo "  ✓ $name — backdrop uploaded";;
      *)  echo "  ! $name — backdrop not accepted (HTTP $code; Plex build may not support collection backgrounds)";;
    esac
  fi
  [ "$line_ok" = 1 ] && up=$((up+1)) || true
done

echo "Done: $up processed, $sk skipped, $miss unmatched, $fail failed."
