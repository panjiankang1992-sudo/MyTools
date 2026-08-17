#!/usr/bin/env bash
set -euo pipefail
out_cert=''
out_profile=''
while (($#)); do
  case "$1" in
    -outCertChain) out_cert="$2"; shift 2 ;;
    -outProfile) out_profile="$2"; shift 2 ;;
    *) shift ;;
  esac
done
printf 'mock-cert' > "$out_cert"
printf 'mock-profile' > "$out_profile"
