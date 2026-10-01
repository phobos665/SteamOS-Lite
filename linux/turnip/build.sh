#!/bin/bash
# Builds Turnip (Mesa's Adreno Vulkan driver) for the Linux runtime: glibc aarch64, KGSL, with X11
# and Wayland surfaces, since Steam, gamescope and every game in the session are Linux processes
# and cannot load the Android builds adrenotools takes.
#
#   linux/turnip/build.sh <variant> <mesa repo> <mesa ref> <out dir>
#
# Variants follow StevenMX's Adreno-Tools-Drivers builds (the v26.x "R" line is Mesa main with
# these changes), so the same driver can be had on both sides:
#   a7xx  Mesa as it is: Adreno 7xx (Snapdragon 8 Gen 1-3).
#   a8xx  The gen8 fork of Turnip (whitebelyash/mesa-tu8, branch gen8): Adreno 8xx (8 Elite).
#   a6xx  No cached-coherent memory (a6xx instability) and sysmem rendering forced (no GMEM).
# Every variant gets the runtime's own KGSL patches (patches/), which the session needs: a DRM
# device identity for gamescope's dma-buf feedback, and no crash when a game asks for calibrated
# timestamps. Also an ir3 shader fix for the Adreno 740, and every chip id an Adreno 830 reports.
# A patch that no longer applies fails the build rather than shipping without it.
#
# Run on an aarch64 host. The zip holds libvulkan_freedreno.so and meta.json; the app writes the
# ICD manifest on import, since library_path must be the path the driver ends up at.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)

variant=${1:?variant}
repo=${2:?mesa repo}
ref=${3:?mesa ref}
out=$(realpath -m "${4:?out dir}")
work=${WORK:-$PWD/turnip-work}/$variant
mkdir -p "$work" "$out"
cd "$work"

rm -rf mesa
git init -q mesa
git -C mesa fetch -q --depth 1 "$repo" "$ref"
git -C mesa checkout -q FETCH_HEAD
cd mesa
commit=$(git rev-parse --short=10 HEAD)
version=$(cat VERSION)

for p in "$here"/patches/*.patch; do
  patch -p1 --forward --fuzz=2 < "$p" || { echo "$(basename "$p") does not apply to $repo@$commit" >&2; exit 1; }
done

# Every id an Adreno 830 reports (revision 0 and 1, by KGSL and msm), only those Mesa lacks.
devices=src/freedreno/common/freedreno_devices.py
anchor='GPUId(chip_id=0xffff44050000, name="Adreno (TM) 830"),'
grep -qF "$anchor" "$devices" || { echo "no Adreno 830 entry in $devices" >&2; exit 1; }
for id in 0x44050001 0x44050000 0xffff44050001; do
  grep -q "chip_id=$id," "$devices" || sed -i "/$(printf %s "$anchor" | sed 's/[]\/$*.^[]/\\&/g')/a\\        GPUId(chip_id=$id, name=\"Adreno (TM) 830\")," "$devices"
done

case $variant in
  a7xx|a8xx) ;;
  a6xx)
    # Found by what they contain, not by file name: Mesa moves these between releases. Each change
    # must land somewhere, or the variant would quietly be the a7xx build.
    tu=src/freedreno/vulkan
    edit() { # <pattern> <sed expression> [grep options]
      local files
      files=$(grep -rlF "${@:3}" -- "$1" "$tu") || { echo "a6xx: '$1' is gone from $tu" >&2; exit 1; }
      sed -i "$2" $files
    }
    # Callers only: the helper itself is defined in a header, and renaming that would clash.
    edit 'tu_bo_init_new_cached(' 's/tu_bo_init_new_cached(/tu_bo_init_new(/g' --include='*.cc'
    edit 'has_cached_coherent_memory =' 's/physical_device->has_cached_coherent_memory = .*/physical_device->has_cached_coherent_memory = false;/'
    edit VK_MEMORY_PROPERTY_HOST_CACHED_BIT 's/dev->physical_device->has_cached_coherent_memory ? VK_MEMORY_PROPERTY_HOST_CACHED_BIT : 0/0/g; s/VK_MEMORY_PROPERTY_HOST_CACHED_BIT/0/g'
    edit 'if (TU_DEBUG(SYSMEM)) {' '/if (TU_DEBUG(SYSMEM)) {/i \   return true;'
    ;;
  *) echo "unknown variant $variant" >&2; exit 64 ;;
esac

# Distro wayland-protocols and libdrm trail Mesa main; Mesa's own wraps build the versions it wants.
meson setup build \
  --prefix /usr \
  --force-fallback-for=wayland-protocols,libdrm \
  -Dbuildtype=release \
  -Db_ndebug=true \
  -Dplatforms=x11,wayland \
  -Dvulkan-drivers=freedreno \
  -Dfreedreno-kmds=kgsl \
  -Dvulkan-beta=true \
  -Dgallium-drivers= \
  -Dopengl=false \
  -Degl=disabled \
  -Dglx=disabled \
  -Dgbm=disabled \
  -Dgles1=disabled \
  -Dgles2=disabled \
  -Dllvm=disabled \
  -Dvideo-codecs= \
  -Dvulkan-layers= \
  -Dtools= \
  -Dbuild-tests=false \
  -Dvalgrind=disabled \
  -Dlibunwind=disabled \
  -Dzstd=enabled
ninja -C build

lib=build/src/freedreno/vulkan/libvulkan_freedreno.so
strip --strip-unneeded "$lib"
# The oldest glibc that can load it; a runtime with an older one cannot.
min_glibc=$(objdump -T "$lib" | grep -o 'GLIBC_[0-9.]*' | sed 's/GLIBC_//' | sort -V | tail -1)
vk=$(cat build/src/freedreno/vulkan/freedreno_icd*.json 2>/dev/null | sed -n 's/.*"api_version": *"\([^"]*\)".*/\1/p' | head -1)

name="Turnip-$version-$commit-$variant-Linux"
pkg=$work/pkg
rm -rf "$pkg" && mkdir -p "$pkg"
cp "$lib" "$pkg/libvulkan_freedreno.so"
cat > "$pkg/meta.json" <<EOF
{
  "kind": "linux-vulkan-icd",
  "name": "Turnip $version ($variant)",
  "driverVersion": "$version-$commit",
  "vulkanVersion": "${vk:-unknown}",
  "variant": "$variant",
  "gpus": "$(case $variant in a6xx) echo "Adreno 6xx" ;; a7xx) echo "Adreno 7xx (Snapdragon 8 Gen 1-3)" ;; a8xx) echo "Adreno 8xx (Snapdragon 8 Elite)" ;; esac)",
  "mesaRepo": "$repo",
  "mesaCommit": "$(git rev-parse HEAD)",
  "minGlibc": "$min_glibc",
  "libraryName": "libvulkan_freedreno.so"
}
EOF
(cd "$pkg" && zip -q -9 "$out/$name.zip" libvulkan_freedreno.so meta.json)
echo "$out/$name.zip (glibc >= $min_glibc, Vulkan ${vk:-?})"
