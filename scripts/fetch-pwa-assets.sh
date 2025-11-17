#!/usr/bin/env bash
#
# scripts/fetch-pwa-assets.sh
# Fetch PWA-related assets (index.html, webjs.js, sw.js, gjw.html) from remote repos.
# Only overwrite local asset files if the remote content differs (sha256 checksum).
#
# Usage:
#   NBR=master SZR=<commit-or-branch> ./scripts/fetch-pwa-assets.sh
#
set -euo pipefail

# Configurable via environment (defaults)
NBR="${NBR:-master}"   # branch/commit for szzdmj/nanohttpd (index.html)
SZR="${SZR:-b6977c914a9a1aabafb5bddd6817eea85a2a8738}"  # commit/branch for szmj0 repo (webjs/sw/gjw)
# Remote bases
BASE_NANO="https://raw.githubusercontent.com/szzdmj/nanohttpd/${NBR}/android-asset-webshell/app/src/main/assets"
BASE_SZ="https://raw.githubusercontent.com/szmj0/szmj0.github.io/${SZR}"

# Target asset directories to keep in sync (both locations)
TARGETS=(
  "smartyoutubetv/src/main/assets"
  "pwaapp/src/main/assets"
)

# Files to fetch from remotes (mapping remote filename -> target filename)
FILES_NANO=("index.html")
FILES_SZ=("webjs.js" "sw.js" "gjw.html")

# Helpers
sha256_cmd() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    # fallback to openssl if available
    if command -v openssl >/dev/null 2>&1; then
      openssl dgst -sha256 "$1" | awk '{print $2}'
    else
      echo "ERROR: no sha256 utility found (sha256sum/shasum/openssl)"; exit 2
    fi
  fi
}

download_to_temp() {
  local url="$1"; local out="$2"
  # Use curl with retries and timeouts
  curl -fsSL --retry 3 --connect-timeout 15 -o "${out}" "${url}"
}

# Ensure target directories exist
for t in "${TARGETS[@]}"; do
  mkdir -p "${t}"
done

changed_files=()
failed_downloads=()

echo "Starting fetch-pwa-assets.sh"
echo "NBR=${NBR}, SZR=${SZR}"
echo "BASE_NANO=${BASE_NANO}"
echo "BASE_SZ=${BASE_SZ}"

# General function to process one remote file and write to all targets if changed
process_remote_file() {
  local remote_url="$1"
  local filename="$2"

  echo "--- Processing ${filename} from ${remote_url}"

  tmpfile="$(mktemp)"
  trap 'rm -f "$tmpfile"' RETURN

  if ! download_to_temp "${remote_url}" "${tmpfile}"; then
    echo "Warning: failed to download ${remote_url}"
    failed_downloads+=("${filename}")
    # Only fail if no local copy exists in any target
    local local_exists=false
    for t in "${TARGETS[@]}"; do
      if [ -f "${t}/${filename}" ]; then local_exists=true; break; fi
    done
    if ! ${local_exists}; then
      echo "ERROR: remote ${filename} unavailable and no local copy exists -> aborting"
      rm -f "${tmpfile}"
      return 2
    else
      echo "Will continue using existing local copy for ${filename}"
      rm -f "${tmpfile}"
      return 0
    fi
  fi

  remote_sha=$(sha256_cmd "${tmpfile}")
  echo "Remote sha256: ${remote_sha}"

  # Compare against each local target; if any target missing or differs, we will overwrite all targets
  local any_different=false
  for t in "${TARGETS[@]}"; do
    local localpath="${t}/${filename}"
    if [ ! -f "${localpath}" ]; then
      echo "Local missing: ${localpath}"
      any_different=true
      break
    fi
    local local_sha
    local_sha=$(sha256_cmd "${localpath}")
    if [ "${local_sha}" != "${remote_sha}" ]; then
      echo "Local differs: ${localpath} (local ${local_sha} != remote ${remote_sha})"
      any_different=true
      break
    fi
  done

  if ${any_different}; then
    # Replace all targets atomically
    for t in "${TARGETS[@]}"; do
      target="${t}/${filename}"
      tmpdst="$(mktemp "${t}/${filename}.tmp.XXXX")" || tmpdst="${t}/${filename}.tmp"
      # ensure dest dir exists
      mkdir -p "$(dirname "${target}")"
      cp "${tmpfile}" "${tmpdst}"
      mv -f "${tmpdst}" "${target}"
      echo "Wrote updated ${target}"
    done
    changed_files+=("${filename}")
  else
    echo "No change for ${filename}"
  fi

  rm -f "${tmpfile}"
  trap - RETURN
  return 0
}

# Fetch from nano (index.html)
for f in "${FILES_NANO[@]}"; do
  rurl="${BASE_NANO}/${f}"
  if ! process_remote_file "${rurl}" "${f}"; then
    # if process_remote_file returns 2 -> fatal
    echo "Fatal: failed to ensure ${f}"
    exit 2
  fi
done

# Fetch from szmj0 (webjs, sw, gjw)
for f in "${FILES_SZ[@]}"; do
  rurl="${BASE_SZ}/${f}"
  if ! process_remote_file "${rurl}" "${f}"; then
    echo "Fatal: failed to ensure ${f}"
    exit 2
  fi
done

# Summary
echo "=== Summary ==="
if [ ${#changed_files[@]} -eq 0 ]; then
  echo "No files changed."
else
  echo "Changed files:"
  for cf in "${changed_files[@]}"; do
    echo " - ${cf}"
  done
fi

if [ ${#failed_downloads[@]} -ne 0 ]; then
  echo "Warning: some files failed to download:"
  for fd in "${failed_downloads[@]}"; do
    echo " - ${fd}"
  done
fi

echo "Done."
exit 0
