#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == 'list' && "${2:-}" == 'targets' ]]; then
  echo 'mock-device-001'
elif [[ "${1:-}" == '-t' && "${3:-}" == 'install' ]]; then
  echo 'Install successfully'
elif [[ "${1:-}" == '-t' && "${3:-}" == 'shell' && "${4:-}" == 'aa' ]]; then
  echo 'start ability successfully'
elif [[ "${1:-}" == '-t' && "${3:-}" == 'shell' && "${4:-}" == 'pidof' ]]; then
  echo '4242'
else
  exit 1
fi
