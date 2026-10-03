#!/usr/bin/env bash
set -euo pipefail

get_sccache_ver() {
  curl -fsSL --retry 3 --retry-all-errors \
    'https://api.github.com/repos/mozilla/sccache/releases/latest' |
    jq -er .tag_name
}

# $1=variant
# $2=install_dir
# $3=exe
install_from_gh() {
  local variant="$1"
  local install_dir="$2"
  local exe="$3"
  local ver
  local url
  local dest
  local staged

  ver="$(get_sccache_ver)"
  url="https://github.com/mozilla/sccache/releases/download/${ver}/sccache-${ver}-${variant}.tar.gz"
  dest="${install_dir}/${exe}"
  staged="${dest}.tmp"

  mkdir -p "$install_dir"
  rm -f "$staged"
  curl -fL --retry 3 --retry-all-errors --retry-delay 2 "$url" |
    tar xz -O --wildcards "*/${exe}" > "$staged"
  chmod +x "$staged"
  "$staged" --version
  mv -f "$staged" "$dest"
}

if [[ "${RUNNER_OS:-}" == "macOS" ]]; then
  brew install sccache
elif [[ "${RUNNER_OS:-}" == "Linux" ]]; then
  install_from_gh x86_64-unknown-linux-musl /usr/local/bin sccache
elif [[ "${RUNNER_OS:-}" == "Windows" ]]; then
  install_from_gh x86_64-pc-windows-msvc "$USERPROFILE/.cargo/bin" sccache.exe
else
  echo "Unsupported runner OS: ${RUNNER_OS:-unknown}" >&2
  exit 1
fi
