#!/usr/bin/env bash
# QA state captures: tools/shots.sh OUTDIR zh|en NAME... (each: set up, wait, capture). DEV = the borrowed emulator.
#   NAME: S01 S02 S03 S05 S06 S07 S08 S09 S10 S11 · h<hour>[n0|n1] = S04 on the sample day at that hour (night overlay)
#         tag-sky / tag-tree / tag-house · rtag-* (h16 rain) / stag-* (h31 snow) · sParis sLondon sTokyo sBeijing · flash19 · empty (search zzzz) · full (4th city -> replace ask)
set -u
OUT="$1"; LANG_="$2"; shift 2
K="$(dirname "$0")/ws.sh"
mkdir -p "$OUT"
"$K" cmd lang "$LANG_"; "$K" cmd welcome; "$K" cmd night off
for S in "$@"; do
  "$K" cmd page weather
  case "$S" in
    h*) H="${S#h}"; N=""; case "$H" in *n0) N=0; H="${H%n0}";; *n1) N=1; H="${H%n1}";; esac
        "$K" cmd now 0; sleep 1; "$K" cmd scrub "$H"; [ -n "$N" ] && "$K" cmd night "$N" ;;
    rtag-*) "$K" cmd now 0; sleep 1; "$K" cmd scrub 16; sleep 2; "$K" cmd tag "${S#rtag-}"; sleep 0.6 ;;
    stag-*) "$K" cmd now 0; sleep 1; "$K" cmd scrub 31; sleep 2; "$K" cmd tag "${S#stag-}"; sleep 0.6 ;;
    sParis) "$K" cmd search Paris ;; sLondon) "$K" cmd search London ;; sTokyo) "$K" cmd search 东京 ;; sBeijing) "$K" cmd search 北京 ;;
    tag-*) "$K" cmd now 0; sleep 1; "$K" cmd scrub 11; sleep 1; "$K" cmd tag "${S#tag-}"; sleep 0.6 ;;
    flash19) "$K" cmd now 0; sleep 1; "$K" cmd scrub 19; sleep 3; "$K" cmd flash 4000; sleep 1 ;;
    empty) "$K" cmd search zzzz ;;
    S07) "$K" cmd search 杭州 ;;
    S11) "$K" cmd closepanel ;;
    *) "$K" cmd goto "$S" ;;
  esac
  case "$S" in tag-*|rtag-*|stag-*|flash19) sleep 0.1 ;; *) sleep "${WAIT:-5}" ;; esac
  for try in 1 2 3; do "$K" shot "$OUT/$S-$LANG_.png" 2>/dev/null && [ -s "$OUT/$S-$LANG_.png" ] && break; sleep 2; done
  case "$S" in S11) "$K" cmd reopen; sleep 4 ;; S10) "$K" cmd page weather ;; S08) "$K" cmd offline 0; sleep 2 ;; h*n*) "$K" cmd night off ;; esac
  echo "$S $LANG_"
done
