#!/bin/sh
set -e

function check_uncommitted_changes() {
  status=$(git status --porcelain)
  if [ -n "$status" ]; then
    status="${status//'%'/'%25'}"
    status="${status//$'\n'/'%0A'}"
    status="${status//$'\r'/'%0D'}"
    echo "$status"
    exit 1
  else
    echo ""
    exit 0
  fi
}

echo ::set-output name=changes::$(check_uncommitted_changes)