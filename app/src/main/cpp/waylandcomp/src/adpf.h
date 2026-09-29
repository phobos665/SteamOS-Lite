#pragma once
#include <stdint.h>

/* Compositor thread, once per drawn frame; target_ns is the panel's frame period. */
void adpf_frame(int64_t target_ns);
