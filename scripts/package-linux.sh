#!/usr/bin/env bash
set -euo pipefail

version="0.1.5"
repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
app_image="${1:-$repository_root/.build/linux-x64/desktopApp/compose/binaries/main/app/WFTools-PC}"
output_directory="${2:-$repository_root/artifacts/package-$(date -u +%Y%m%d-%H%M%S)-linux-x64}"

fail() { echo "错误：$*" >&2; exit 1; }
normalize_app_permissions() {
  local root="$1"
  find "$root" -type d -exec chmod 0755 {} +
  find "$root" -type f -exec chmod 0644 {} +
  find "$root/bin" -maxdepth 1 -type f -exec chmod 0755 {} +
  [[ ! -d "$root/lib/runtime/bin" ]] || find "$root/lib/runtime/bin" -type f -exec chmod 0755 {} +
  for helper in "$root/lib/runtime/lib/jexec" "$root/lib/runtime/lib/jspawnhelper"; do
    [[ ! -f "$helper" ]] || chmod 0755 "$helper"
  done
}
[[ "$(uname -s)" == "Linux" && "$(uname -m)" == "x86_64" ]] || fail "打包脚本仅支持 Linux x86_64。"
for command_name in dpkg-deb fakeroot file readelf desktop-file-validate sha256sum tar jq python3; do
  command -v "$command_name" >/dev/null || fail "缺少打包工具：$command_name"
done
app_image="$(realpath -e "$app_image")"
[[ -d "$app_image" && -x "$app_image/bin/WFTools-PC" ]] || fail "无效的 Compose Linux 应用目录：$app_image"
file "$app_image/bin/WFTools-PC" | grep -q 'ELF 64-bit.*x86-64' || fail "启动器不是 Linux x86_64 ELF。"
[[ ! -e "$output_directory" ]] || fail "输出目录已存在，拒绝覆盖：$output_directory"
mkdir -p "$output_directory"
output_directory="$(realpath "$output_directory")"
stage="$(mktemp -d "${TMPDIR:-/tmp}/wftools-linux-package.XXXXXX")"
trap 'rm -rf -- "$stage"' EXIT

portable_name="WFTools-PC-${version}-linux-x64-portable"
portable_root="$stage/$portable_name"
mkdir -p "$portable_root/app"
cp -a "$app_image/." "$portable_root/app/"
normalize_app_permissions "$portable_root/app"
cp "$repository_root/packaging/README_LINUX.md" "$portable_root/README_LINUX.md"
chmod 0644 "$portable_root/README_LINUX.md"
cat > "$portable_root/run-wftools.sh" <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
missing=()
for tool in vlc ffmpeg ffprobe gio xdg-mime update-mime-database update-desktop-database; do
  command -v "$tool" >/dev/null || missing+=("$tool")
done
if ! ldconfig -p 2>/dev/null | grep 'libvlc\.so' >/dev/null; then missing+=("libvlc"); fi
if ! command -v fc-list >/dev/null || [[ -z "$(fc-list :lang=zh family 2>/dev/null | sed -n '1p')" ]]; then missing+=("中文字体"); fi
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "WFTools-PC 缺少 Linux 媒体依赖：${missing[*]}" >&2
  echo "Ubuntu/Debian 可执行：sudo apt install vlc libvlc5 vlc-plugin-base ffmpeg xdg-utils shared-mime-info libglib2.0-bin desktop-file-utils fontconfig fonts-noto-cjk" >&2
  exit 3
fi
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$root/app/bin/WFTools-PC" "$@"
LAUNCHER
chmod 0755 "$portable_root/run-wftools.sh"
(
  cd "$portable_root"
  find app -type f -print0 | sort -z | xargs -0 sha256sum > APP-CONTENTS.sha256
)

deb_root="$stage/deb-root"
mkdir -p "$deb_root/DEBIAN" "$deb_root/opt/wftools-pc" "$deb_root/usr/bin" \
  "$deb_root/usr/share/applications" "$deb_root/usr/share/mime/packages" \
  "$deb_root/usr/share/icons/hicolor/512x512/apps" "$deb_root/usr/share/doc/wftools-pc"
cp -a "$app_image/." "$deb_root/opt/wftools-pc/"
normalize_app_permissions "$deb_root/opt/wftools-pc"
cp "$repository_root/desktopApp/src/main/resources/icons/wftools.png" "$deb_root/usr/share/icons/hicolor/512x512/apps/wftools-pc.png"
cp "$repository_root/packaging/README_LINUX.md" "$deb_root/usr/share/doc/wftools-pc/README.md"
cat > "$deb_root/usr/bin/wftools-pc" <<'LAUNCHER'
#!/usr/bin/env bash
set -euo pipefail
exec /opt/wftools-pc/bin/WFTools-PC "$@"
LAUNCHER
chmod 0755 "$deb_root/usr/bin/wftools-pc"
cat > "$deb_root/usr/share/applications/wftools-pc.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=WFTools-PC
Comment=WatchMaker watch face editor
Exec=wftools-pc %f
Icon=wftools-pc
Terminal=false
Categories=Graphics;
MimeType=application/x-wftools-watch;
StartupWMClass=WFTools-PC
DESKTOP
cat > "$deb_root/usr/share/mime/packages/wftools-pc-watch.xml" <<'MIME'
<?xml version="1.0" encoding="UTF-8"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
  <mime-type type="application/x-wftools-watch">
    <comment>WFTools-PC WatchMaker watch face</comment>
    <glob pattern="*.watch"/>
  </mime-type>
</mime-info>
MIME
cat > "$deb_root/DEBIAN/control" <<CONTROL
Package: wftools-pc
Version: $version
Section: graphics
Priority: optional
Architecture: amd64
Maintainer: WFTools-PC
Depends: libvlc5, vlc-bin, vlc-plugin-base, ffmpeg, xdg-utils, shared-mime-info, libglib2.0-bin, desktop-file-utils, fontconfig, fonts-noto-cjk
Description: WatchMaker watch face editor
 A Compose Desktop editor for WatchMaker .watch projects.
CONTROL
cat > "$deb_root/DEBIAN/postinst" <<'POSTINST'
#!/bin/sh
set -e
command -v update-mime-database >/dev/null 2>&1 && update-mime-database /usr/share/mime || true
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database /usr/share/applications || true
exit 0
POSTINST
cat > "$deb_root/DEBIAN/postrm" <<'POSTRM'
#!/bin/sh
set -e
command -v update-mime-database >/dev/null 2>&1 && update-mime-database /usr/share/mime || true
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database /usr/share/applications || true
exit 0
POSTRM
chmod 0755 "$deb_root/DEBIAN/postinst" "$deb_root/DEBIAN/postrm"
chmod 0644 "$deb_root/DEBIAN/control"
find "$deb_root/usr/share" -type d -exec chmod 0755 {} +
find "$deb_root/usr/share" -type f -exec chmod 0644 {} +

desktop-file-validate "$deb_root/usr/share/applications/wftools-pc.desktop"
python3 -c 'import sys,xml.etree.ElementTree as E; E.parse(sys.argv[1])' "$deb_root/usr/share/mime/packages/wftools-pc-watch.xml"

portable_archive="$output_directory/${portable_name}.tar.gz"
deb_archive="$output_directory/WFTools-PC-${version}-linux-x64.deb"
tar -C "$stage" -czf "$portable_archive" "$portable_name"
fakeroot dpkg-deb --root-owner-group --build "$deb_root" "$deb_archive" >/dev/null

dpkg-deb --field "$deb_archive" Architecture | grep -qx amd64 || fail "DEB 架构校验失败。"
dpkg-deb --field "$deb_archive" Version | grep -qx "$version" || fail "DEB 版本校验失败。"
dpkg-deb --field "$deb_archive" Depends | grep -q 'libvlc5' || fail "DEB 缺少 LibVLC 依赖。"
dpkg-deb --field "$deb_archive" Depends | grep -q 'fonts-noto-cjk' || fail "DEB 缺少中文字体依赖。"
dpkg-deb --field "$deb_archive" Depends | grep -q 'libglib2.0-bin' || fail "DEB 缺少 gio 依赖。"
dpkg-deb --field "$deb_archive" Depends | grep -q 'desktop-file-utils' || fail "DEB 缺少桌面数据库依赖。"
deb_contents="$(dpkg-deb --contents "$deb_archive")"
grep -q './usr/share/applications/wftools-pc.desktop' <<<"$deb_contents" || fail "DEB 缺少桌面入口。"
portable_contents="$(tar -tzf "$portable_archive")"
grep -q "$portable_name/run-wftools.sh" <<<"$portable_contents" || fail "便携包缺少启动器。"

(
  cd "$output_directory"
  sha256sum "$(basename "$deb_archive")" "$(basename "$portable_archive")" > SHA256SUMS
)
built_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
jq -n \
  --arg name "WFTools-PC" --arg version "$version" --arg platform "linux-x64" --arg builtAt "$built_at" \
  --arg deb "$(basename "$deb_archive")" --arg portable "$(basename "$portable_archive")" \
  --arg debSha256 "$(sha256sum "$deb_archive" | cut -d' ' -f1)" \
  --arg portableSha256 "$(sha256sum "$portable_archive" | cut -d' ' -f1)" \
  '{name:$name,version:$version,platform:$platform,builtAt:$builtAt,artifacts:[{file:$deb,format:"deb",sha256:$debSha256},{file:$portable,format:"tar.gz",sha256:$portableSha256}],systemDependencies:["libvlc5","vlc-bin","vlc-plugin-base","ffmpeg","xdg-utils","shared-mime-info","libglib2.0-bin","desktop-file-utils","fontconfig","fonts-noto-cjk"]}' \
  > "$output_directory/PACKAGE-MANIFEST.json"

echo "$output_directory"
