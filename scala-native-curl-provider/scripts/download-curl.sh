#!/bin/bash
# Downloads static curl libraries from kubuszok/curl-natives for all 6 desktop platforms.
# These are built with platform-native toolchains (MSVC on Windows, GCC/clang on Linux/macOS)
# to ensure ABI compatibility with each platform's linker.
#
# Usage: ./download-curl.sh [version]
# Default version: curl-8.19.0

set -euo pipefail

CURL_VERSION="${1:-curl-8.19.0}"
CURL_REPO="kubuszok/curl-natives"
BASE_URL="https://github.com/$CURL_REPO/releases/download/${CURL_VERSION}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
NATIVES_DIR="${SCRIPT_DIR}/../natives"

PLATFORMS="linux-x86_64 linux-aarch64 macos-x86_64 macos-aarch64 windows-x86_64 windows-aarch64"

echo "=== Downloading curl from $CURL_REPO ($CURL_VERSION) ==="

for platform in $PLATFORMS; do
  archive="curl-${platform}.tar.gz"
  url="${BASE_URL}/${archive}"
  dest_dir="${NATIVES_DIR}/${platform}"

  # Clean and recreate
  rm -rf "${dest_dir}"
  mkdir -p "${dest_dir}"

  echo "--- $platform ---"
  tmp_dir=$(mktemp -d)

  if curl -fSL -o "${tmp_dir}/${archive}" "${url}"; then
    tar xzf "${tmp_dir}/${archive}" -C "${tmp_dir}"

    # Copy static library from lib/ directory
    lib_dir="${tmp_dir}/lib"
    if [ -d "${lib_dir}" ]; then
      # Linux/macOS: libcurl.a
      for f in "${lib_dir}"/*.a; do
        [ -f "$f" ] && cp "$f" "${dest_dir}/"
      done
      # Windows: .lib files
      for f in "${lib_dir}"/*.lib; do
        [ -f "$f" ] && cp "$f" "${dest_dir}/"
      done
      echo "  Done: $(ls "${dest_dir}" | wc -l | tr -d ' ') files"
    else
      echo "  WARNING: lib/ directory not found in archive"
    fi
  else
    echo "  WARNING: Download failed, skipping"
  fi

  rm -rf "${tmp_dir}"
done

# Create dummy libidn2 archives for all platforms.
# sttp declares @link("idn2") which emits -lidn2 at link time.
# The actual idn2 symbols are statically linked inside libcurl, so
# we just need an archive that satisfies the linker's -lidn2 lookup.
echo ""
echo "=== Creating idn2 stub archives ==="
STUBS_DIR="${SCRIPT_DIR}/../stubs"

for platform in $PLATFORMS; do
  dest_dir="${NATIVES_DIR}/${platform}"
  [ -d "${dest_dir}" ] || continue

  # Copy platform-specific pre-built stub from stubs/ directory.
  # Each platform needs its own object format (Mach-O, ELF, COFF).
  for stub in "${STUBS_DIR}/${platform}"/*; do
    [ -f "$stub" ] && cp "$stub" "${dest_dir}/"
  done
  echo "  ${platform}: $(ls "${STUBS_DIR}/${platform}" 2>/dev/null | tr '\n' ' ')"
done

echo ""
echo "=== Summary ==="
for platform in $PLATFORMS; do
  dir="${NATIVES_DIR}/${platform}"
  if [ -d "${dir}" ]; then
    echo "  ${platform}: $(ls "${dir}" 2>/dev/null | tr '\n' ' ')"
  else
    echo "  ${platform}: not downloaded"
  fi
done
