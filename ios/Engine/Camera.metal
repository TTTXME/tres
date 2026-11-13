// The merge's camera RGB (Android scam::hybridReconstruct output: float32 RGB rows, base-frame units) → white-balanced
// linear sRGB for «Why тон» V2 (its v2_clean then runs with identity matrices).
#include <metal_stdlib>
#include "ShaderTypes.h"
using namespace metal;

kernel void scam_camera_to_linear(device const float *rgb [[buffer(0)]],
                                  constant CameraToLinearParams &p [[buffer(1)]],
                                  texture2d<float, access::write> output [[texture(0)]],
                                  uint2 gid [[thread_position_in_grid]]) {
    if (int(gid.x) >= p.width || int(gid.y) >= p.height) return;
    const uint i = (gid.y * uint(p.width) + gid.x) * 3;
    float3 c = float3(rgb[i], rgb[i + 1], rgb[i + 2]);
    c = float3(isfinite(c.r) ? c.r : 0.0, isfinite(c.g) ? c.g : 0.0, isfinite(c.b) ? c.b : 0.0);
    c = p.cameraToSRGB * (c * p.neutralInverse);
    output.write(float4(c, 1.0), gid);
}
