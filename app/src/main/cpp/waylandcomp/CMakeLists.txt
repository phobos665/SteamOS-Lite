# The embedded Wayland compositor gamescope presents into (lifted from Bannerlator's waylandcomp).
# Protocol glue is pre-generated in generated/, so no wayland-scanner is needed at build time.
# Frame generation is not built in: framegen_engine_stub.c reports every engine unavailable.
add_library(bannerwayland SHARED
        src/compositor.c
        src/vk_present.c
        src/effects_chain.c
        src/vk_loader.c
        src/sc_layer.c
        src/ahb_swapchain.c
        src/waylandcomp_jni.c
        src/banner_ext.c
        src/wl_clipboard.c
        src/wl_text_input.c
        src/wl_toplevel_icon.c
        src/wl_color_mgmt.c
        src/hdr_compose.c
        src/framegen_bridge.c
        src/framegen_jni.c
        src/framegen_engine_stub.c
        generated/color-management-v1-protocol.c
        generated/xdg-shell-protocol.c
        generated/linux-dmabuf-v1-protocol.c
        generated/viewporter-protocol.c
        generated/banner-desktop-v1-protocol.c
        generated/presentation-time-protocol.c
        generated/pointer-constraints-unstable-v1-protocol.c
        generated/relative-pointer-unstable-v1-protocol.c
        generated/wlr-data-control-unstable-v1-protocol.c
        generated/text-input-unstable-v3-protocol.c
        generated/xdg-toplevel-icon-v1-protocol.c
        generated/banner-ahb-v1-protocol.c
)
target_include_directories(bannerwayland PRIVATE generated prebuilt/include src shaders)

add_library(wayland-server SHARED IMPORTED)
set_target_properties(wayland-server PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/lib/libwayland-server.so)
# Vulkan is loaded at runtime through adrenotools (Turnip), which is what can import dma-bufs.
target_link_libraries(bannerwayland wayland-server adrenotools dl log android)
