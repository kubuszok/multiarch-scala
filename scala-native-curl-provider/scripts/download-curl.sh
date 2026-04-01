#!/bin/bash
# Downloads static curl development libraries from stunnel/static-curl for all 6 desktop platforms.
# The -dev archives contain .a static libraries needed for Scala Native linking.
#
# Usage: ./download-curl.sh [version]
# Default version: 8.12.1

set -euo pipefail

CURL_VERSION="${1:-8.12.1}"
BASE_URL="https://github.com/stunnel/static-curl/releases/download/${CURL_VERSION}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVES_DIR="${SCRIPT_DIR}/../natives"

# Each line: our-classifier stunnel-archive-name archive-internal-dir
PLATFORMS="
linux-x86_64    curl-linux-x86_64-dev-${CURL_VERSION}.tar.xz    curl-x86_64
linux-aarch64   curl-linux-aarch64-dev-${CURL_VERSION}.tar.xz   curl-aarch64
macos-x86_64    curl-macos-x86_64-dev-${CURL_VERSION}.tar.xz    curl-x86_64
macos-aarch64   curl-macos-arm64-dev-${CURL_VERSION}.tar.xz     curl-arm64
windows-x86_64  curl-windows-x86_64-dev-${CURL_VERSION}.tar.xz  curl-x86_64
windows-aarch64 curl-windows-aarch64-dev-${CURL_VERSION}.tar.xz curl-aarch64
"

echo "$PLATFORMS" | while read -r classifier archive_name internal_dir; do
  # Skip empty lines
  [ -z "$classifier" ] && continue

  url="${BASE_URL}/${archive_name}"
  dest_dir="${NATIVES_DIR}/${classifier}"
  mkdir -p "${dest_dir}"

  echo "=== Downloading curl ${CURL_VERSION} for ${classifier} ==="
  echo "    URL: ${url}"

  tmp_dir=$(mktemp -d)

  if curl -fSL -o "${tmp_dir}/${archive_name}" "${url}"; then
    echo "    Extracting..."
    tar xf "${tmp_dir}/${archive_name}" -C "${tmp_dir}"

    # Copy .a files from the lib/ directory inside the archive
    lib_dir="${tmp_dir}/${internal_dir}/lib"
    if [ -d "${lib_dir}" ]; then
      find "${lib_dir}" -maxdepth 1 -name "*.a" -exec cp {} "${dest_dir}/" \;
      count=$(find "${dest_dir}" -maxdepth 1 -name "*.a" | wc -l | tr -d ' ')
      echo "    Done: ${count} libraries"
    else
      echo "    WARNING: lib/ directory not found in archive at ${internal_dir}/lib"
      # Try finding .a files anywhere in the extraction
      find "${tmp_dir}" -name "*.a" -exec cp {} "${dest_dir}/" \;
    fi
  else
    echo "    WARNING: Download failed for ${classifier}, skipping"
  fi

  rm -rf "${tmp_dir}"
done

echo ""
echo "=== Summary ==="
for classifier in linux-x86_64 linux-aarch64 macos-x86_64 macos-aarch64 windows-x86_64 windows-aarch64; do
  dir="${NATIVES_DIR}/${classifier}"
  if [ -d "${dir}" ]; then
    count=$(find "${dir}" -maxdepth 1 -name "*.a" | wc -l | tr -d ' ')
    echo "  ${classifier}: ${count} libraries"
  else
    echo "  ${classifier}: not downloaded"
  fi
done
