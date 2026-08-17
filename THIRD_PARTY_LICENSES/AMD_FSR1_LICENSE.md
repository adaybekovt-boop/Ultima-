# AMD FidelityFX Super Resolution 1 (FSR 1)

Ultima's `fsr_upscaling` module contains an independent GLSL port of AMD FSR 1
**EASU** (Edge Adaptive Spatial Upsampling) and **RCAS** (Robust Contrast
Adaptive Sharpening). The port is from the official portable header, not from
Sodium, Iris, or any other Minecraft mod.

## Source pinned by this tree

| Field | Value |
|---|---|
| Product | AMD FidelityFX Super Resolution 1 (FSR 1) |
| Sample / package version | **1.0.2** |
| Header version string | `v1.20210629` (`ffx_fsr1.h`) |
| Upstream repository | https://github.com/GPUOpen-Effects/FidelityFX-FSR |
| Pinned commit | `a21ffb8f6c13233ba336352bdff293894c706575` |
| Upstream files used as the algorithm reference | `ffx-fsr/ffx_fsr1.h`, `ffx-fsr/ffx_a.h`, `license.txt` |
| License | MIT |

FSR 2, FSR 3, frame generation, and DLSS are **not** included.

The Java constant setup (`FsrEasuCon` / `FsrRcasCon`) and the GLSL filter
bodies are a rewrite of the official 32-bit `*F()` path for Minecraft 26.2's
`#version 330` pipeline. They are not a copy of any GPL/LGPL Minecraft shader
integration.

## MIT license (upstream `license.txt`)

Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
