#!/bin/bash
# Builds Turnip (Mesa's Adreno Vulkan driver) for the Linux runtime: glibc aarch64, KGSL, with X11
# and Wayland surfaces, since Steam, gamescope and every game in the session are Linux processes
# and cannot load the Android builds adrenotools takes.
#
#   linux/turnip/build.sh <variant> <mesa repo> <mesa ref> <out dir>
#
# Variants follow StevenMX's Adreno-Tools-Drivers builds (the v26.x "R" line is Mesa main with
# these changes), so the same driver can be had on both sides:
#   a7xx  Mesa as it is.
#   a6xx  No cached-coherent memory (a6xx instability) and sysmem rendering forced (no GMEM).
#
# Run on an aarch64 host. The zip holds libvulkan_freedreno.so and meta.json; the app writes the
# ICD manifest on import, since library_path must be the path the driver ends up at.
set -euo pipefail

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

case $variant in
  a7xx) ;;
  a6xx)
    sed -i 's/tu_bo_init_new_cached/tu_bo_init_new/g' src/freedreno/vulkan/tu_query.cc
    sed -i 's/physical_device->has_cached_coherent_memory = .*/physical_device->has_cached_coherent_memory = false;/' \
      src/freedreno/vulkan/tu_device.cc
    grep -rl VK_MEMORY_PROPERTY_HOST_CACHED_BIT src/freedreno/vulkan/ | while read -r f; do
      sed -i 's/dev->physical_device->has_cached_coherent_memory ? VK_MEMORY_PROPERTY_HOST_CACHED_BIT : 0/0/g' "$f"
      sed -i 's/VK_MEMORY_PROPERTY_HOST_CACHED_BIT/0/g' "$f"
    done
    grep -q 'if (TU_DEBUG(SYSMEM)) {' src/freedreno/vulkan/tu_cmd_buffer.cc
    sed -i '/if (TU_DEBUG(SYSMEM)) {/i \   return true;' src/freedreno/vulkan/tu_cmd_buffer.cc
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
  "mesaRepo": "$repo",
  "mesaCommit": "$(git rev-parse HEAD)",
  "minGlibc": "$min_glibc",
  "libraryName": "libvulkan_freedreno.so"
}
EOF
(cd "$pkg" && zip -q -9 "$out/$name.zip" libvulkan_freedreno.so meta.json)
echo "$out/$name.zip (glibc >= $min_glibc, Vulkan ${vk:-?})"
