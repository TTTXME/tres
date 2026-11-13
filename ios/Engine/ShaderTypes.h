// Parameter blocks shared by Swift and the Metal kernels (one layout for both).
#ifndef SCAM_SHADER_TYPES_H
#define SCAM_SHADER_TYPES_H

#include <simd/simd.h>

// why/v2_clean.glsl
typedef struct {
    simd_float3x3 sensorToIntermediate;
    simd_float3x3 intermediateToSRGB;
    simd_float3 neutral;
    float scale;
} WhyV2CleanParams;

// why/v2_ef_prep.glsl (both outputs at once, as the reference's two attachments)
typedef struct {
    simd_float4 expMultipliers;
    simd_float4 layerWeights;
    int width;
    int height;
    int scale;
    int gammaCurveType;
    float weightCenter;
    float autoExposure;
    float blendSmoothness;
    float gammaInv;
    float acesToe;
} WhyV2PrepParams;

// why/v2_pyr_down.glsl, why/v2_ef_coarsest.glsl, why/v2_ef_collapse.glsl
typedef struct {
    simd_float2 texelSize;
    int width;
    int height;
    float clarity;
} WhyV2LevelParams;

// why/v2_tonemap.glsl
typedef struct {
    simd_float3x3 agxInset;
    simd_float3x3 agxOutset;
    int width;
    int height;
    int efScaled;
    int quant8;
    int tonemapOperator;
    int agxLook;
    int agxHueLock;
    int gammaCurveType;
    float autoExposure;
    float clipCeiling;
    float acesToe;
    float agxSlope;
    float agxSp;
    float agxTp;
    float agxMinEv;
    float agxMaxEv;
    float agxSatBoost;
    float agxEv;
    float acesPreGain;
    float acesA;
    float acesD;
    float uchiPreGain;
    float uchiPostGain;
    float uchiP;
    float uchiA;
    float uchiM;
    float uchiL;
    float uchiC;
    float uchiB;
    float macroContrast;
    float contrastBoost;
    float vibStrength;
    float vibSaturated;
    float skinProtect;
    float vibSky;
    float vibGreen;
    float vibHl;
    float vibMid;
    float vibShadow;
    float skinHueShift;
    float skinSatShift;
    float skinHueCenter;
    float skinLumaMin;
    float gamma;
    float gammaInv;
    float filmToe;
    float shoulderStrength;
    float shoulderKnee;
    float shoulderHeadroom;
} WhyV2ToneParams;

#endif
