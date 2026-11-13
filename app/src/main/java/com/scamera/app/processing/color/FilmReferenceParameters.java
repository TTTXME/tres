package com.scamera.app.processing.color;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Scalar film settings shared with the spectral renderer; no native image data. */
public final class FilmReferenceParameters {
    public static final int BYTES=652;
    private final ByteBuffer data=ByteBuffer.allocateDirect(BYTES).order(ByteOrder.LITTLE_ENDIAN);
    public FilmReferenceParameters(){
        data.putInt(0,0); // enabled
        data.putInt(4,0); // process
        data.putInt(8,0); // renderOutput
        data.putInt(12,2); // rgbToRawMethod
        data.putInt(16,15); // inputColorSpace
        data.putInt(20,17); // outputColorSpace
        data.putInt(24,0); // outputRole
        data.putInt(28,0); // hdrPreset
        data.putInt(32,0); // hdrTransfer
        data.putFloat(36,203.0f); // hdrReferenceWhiteNits
        data.putFloat(40,1000.0f); // hdrPeakNits
        data.putFloat(44,0.0f); // hdrExposureEv
        data.putInt(48,1); // hdrToneMapping
        data.putInt(52,0); // colorAdaptation
        data.putInt(56,2); // film
        data.putInt(60,3); // paper
        data.putInt(64,0); // printTiming
        data.putInt(68,0); // cameraUvFilterEnabled
        data.putFloat(72,410.0f); // cameraUvCutNm
        data.putInt(76,0); // cameraIrFilterEnabled
        data.putFloat(80,675.0f); // cameraIrCutNm
        data.putFloat(84,0.0f); // filmExposureEv
        data.putInt(88,0); // autoExposure
        data.putInt(92,0); // autoExposureMethod
        data.putFloat(96,0.0f); // printExposureEv
        data.putInt(100,0); // filmPushPullMode
        data.putFloat(104,0.0f); // filmPushPullStops
        data.putFloat(108,0.0f); // printPushPullStops
        data.putFloat(112,0.0f); // negativeBleachBypassAmount
        data.putFloat(116,1.0f); // negativeLeucoCyanCoupling
        data.putFloat(120,0.0f); // printBleachBypassAmount
        data.putFloat(124,1.0f); // filmGamma
        data.putFloat(128,1.0f); // printGamma
        data.putFloat(132,0.0f); // printShadowShape
        data.putFloat(136,0.0f); // printHighlightShape
        data.putFloat(140,0.0f); // filterC
        data.putFloat(144,0.0f); // filterMShift
        data.putFloat(148,0.0f); // filterYShift
        data.putFloat(152,1.0f); // enlargerScale
        data.putFloat(156,0.0f); // enlargerOffsetXPercent
        data.putFloat(160,0.0f); // enlargerOffsetYPercent
        data.putFloat(164,0.0f); // preflashExposure
        data.putFloat(168,0.0f); // preflashMFilterShift
        data.putFloat(172,0.0f); // preflashYFilterShift
        data.putFloat(176,0.0f); // printerLightsR
        data.putFloat(180,0.0f); // printerLightsG
        data.putFloat(184,0.0f); // printerLightsB
        data.putInt(188,0); // printerLightsGang
        data.putInt(192,1); // printerLightCalibration
        data.putFloat(196,0.0f); // dirCouplersAmount
        data.putFloat(200,20.0f); // dirCouplersDiffusionUm
        data.putFloat(204,200.0f); // dirCouplersDiffusionTailUm
        data.putFloat(208,0.06f); // dirCouplersDiffusionTailWeight
        data.putFloat(212,1.0f); // dirCouplersInhibitionSameLayer
        data.putFloat(216,1.0f); // dirCouplersInhibitionInterlayer
        data.putFloat(220,0.336f); // dirCouplersGammaSameLayerR
        data.putFloat(224,0.319f); // dirCouplersGammaSameLayerG
        data.putFloat(228,0.273f); // dirCouplersGammaSameLayerB
        data.putFloat(232,0.353f); // dirCouplersGammaRToG
        data.putFloat(236,0.302f); // dirCouplersGammaRToB
        data.putFloat(240,0.154f); // dirCouplersGammaGToR
        data.putFloat(244,0.353f); // dirCouplersGammaGToB
        data.putFloat(248,0.168f); // dirCouplersGammaBToR
        data.putFloat(252,0.226f); // dirCouplersGammaBToG
        data.putInt(256,0); // grainEnabled
        data.putInt(260,1); // grainModel
        data.putInt(264,4); // filmFormat
        data.putFloat(268,1.0f); // grainAmount
        data.putFloat(272,1.0f); // grainSaturation
        data.putInt(276,1); // grainSublayersEnabled
        data.putInt(280,1); // grainSubLayerCount
        data.putFloat(284,0.1f); // grainParticleAreaUm2
        data.putFloat(288,1.2f); // grainParticleScaleR
        data.putFloat(292,1.0f); // grainParticleScaleG
        data.putFloat(296,2.5f); // grainParticleScaleB
        data.putFloat(300,6.0f); // grainParticleScaleLayer0
        data.putFloat(304,1.0f); // grainParticleScaleLayer1
        data.putFloat(308,0.4f); // grainParticleScaleLayer2
        data.putFloat(312,0.04f); // grainDensityMinR
        data.putFloat(316,0.05f); // grainDensityMinG
        data.putFloat(320,0.06f); // grainDensityMinB
        data.putFloat(324,0.99f); // grainUniformityR
        data.putFloat(328,0.97f); // grainUniformityG
        data.putFloat(332,0.98f); // grainUniformityB
        data.putFloat(336,11.8f); // grainFinalBlurUm
        data.putFloat(340,1.0f); // grainBlurDyeCloudsUm
        data.putFloat(344,0.2f); // grainMicroStructureScale
        data.putFloat(348,30.0f); // grainMicroStructureSigmaNm
        data.putInt(352,1); // grainSeed
        data.putInt(356,0); // grainAnimate
        data.putFloat(360,1.0f); // grainSynthesisSize
        data.putFloat(364,1.0f); // grainSynthesisAmount
        data.putFloat(368,1.0f); // grainSynthesisSharpness
        data.putFloat(372,1.0f); // grainSynthesisQuality
        data.putInt(376,128); // grainSynthesisSamples
        data.putFloat(380,0.25f); // grainSynthesisMeanRadiusUm
        data.putFloat(384,0.0f); // grainSynthesisRadiusStdDevRatio
        data.putFloat(388,1.0f); // grainSynthesisObservationSigmaUm
        data.putFloat(392,1.0f); // grainSynthesisCellSizeRatio
        data.putFloat(396,0.999f); // grainSynthesisMaxRadiusQuantile
        data.putFloat(400,0.0001f); // grainSynthesisCoverageEpsilon
        data.putInt(404,32); // grainSynthesisMaxGrainsPerCell
        data.putFloat(408,1.2f); // grainSynthesisRadiusScaleR
        data.putFloat(412,1.0f); // grainSynthesisRadiusScaleG
        data.putFloat(416,2.5f); // grainSynthesisRadiusScaleB
        data.putFloat(420,6.0f); // grainSynthesisLayerScale0
        data.putFloat(424,1.0f); // grainSynthesisLayerScale1
        data.putFloat(428,0.4f); // grainSynthesisLayerScale2
        data.putInt(432,1); // grainSynthesisLayered
        data.putInt(436,0); // halationEnabled
        data.putFloat(440,1.0f); // scatterAmount
        data.putFloat(444,1.0f); // scatterScale
        data.putFloat(448,1.0f); // halationAmount
        data.putFloat(452,1.0f); // halationScale
        data.putFloat(456,0.05f); // halationStrengthR
        data.putFloat(460,0.015f); // halationStrengthG
        data.putFloat(464,0.0f); // halationStrengthB
        data.putFloat(468,65.0f); // halationFirstSigmaUmR
        data.putFloat(472,65.0f); // halationFirstSigmaUmG
        data.putFloat(476,65.0f); // halationFirstSigmaUmB
        data.putFloat(480,0.0f); // halationBoostEv
        data.putFloat(484,0.3f); // halationBoostRange
        data.putFloat(488,4.0f); // halationProtectEv
        data.putInt(492,0); // cameraDiffusionEnabled
        data.putInt(496,1); // cameraDiffusionFamily
        data.putFloat(500,0.5f); // cameraDiffusionStrength
        data.putFloat(504,1.0f); // cameraDiffusionSpatialScale
        data.putFloat(508,0.0f); // cameraDiffusionHaloWarmth
        data.putFloat(512,1.0f); // cameraDiffusionCoreIntensity
        data.putFloat(516,1.0f); // cameraDiffusionCoreSize
        data.putFloat(520,1.0f); // cameraDiffusionHaloIntensity
        data.putFloat(524,1.0f); // cameraDiffusionHaloSize
        data.putFloat(528,1.0f); // cameraDiffusionBloomIntensity
        data.putFloat(532,1.0f); // cameraDiffusionBloomSize
        data.putInt(536,0); // printDiffusionEnabled
        data.putInt(540,1); // printDiffusionFamily
        data.putFloat(544,0.5f); // printDiffusionStrength
        data.putFloat(548,1.0f); // printDiffusionSpatialScale
        data.putFloat(552,0.0f); // printDiffusionHaloWarmth
        data.putFloat(556,1.0f); // printDiffusionCoreIntensity
        data.putFloat(560,1.0f); // printDiffusionCoreSize
        data.putFloat(564,1.0f); // printDiffusionHaloIntensity
        data.putFloat(568,1.0f); // printDiffusionHaloSize
        data.putFloat(572,1.0f); // printDiffusionBloomIntensity
        data.putFloat(576,1.0f); // printDiffusionBloomSize
        data.putInt(580,0); // scannerEnabled
        data.putInt(584,0); // scannerWhiteCorrection
        data.putInt(588,0); // scannerBlackCorrection
        data.putFloat(592,0.98f); // scannerWhiteLevel
        data.putFloat(596,0.01f); // scannerBlackLevel
        data.putFloat(600,0.03f); // glarePercent
        data.putFloat(604,0.7f); // glareRoughness
        data.putFloat(608,0.5f); // glareBlur
        data.putFloat(612,60.0f); // scannerMtf50LpMm
        data.putFloat(616,5.0f); // scannerUnsharpRadiusUm
        data.putFloat(620,0.7f); // scannerUnsharpAmount
        data.putInt(624,0); // scanNegativeInvert
        data.putInt(628,1); // colorAdaptationInputCompression
        data.putInt(632,1); // colorAdaptationCurveSmoothing
        data.putInt(636,1); // colorAdaptationOutputLightnessCompression
        data.putInt(640,1); // colorAdaptationOutputChromaCompression
        data.putInt(644,0); // gpuRenderTiling
    }
    private static final String[] FILMS={
        "kodak_ektar_100","kodak_portra_160","kodak_portra_400","kodak_portra_800",
        "kodak_portra_800_push1","kodak_portra_800_push2","kodak_gold_200","kodak_ultramax_400",
        "kodak_vision3_50d","kodak_vision3_250d","kodak_verita_200d","kodak_vision3_200t",
        "kodak_vision3_500t","fujifilm_pro_400h","fujifilm_c200","fujifilm_xtra_400",
        "kodak_ektachrome_100","kodak_kodachrome_64","fujifilm_velvia_100","fujifilm_provia_100f"};
    private static final String[] PAPERS={"kodak_endura_premier","kodak_ultra_endura","kodak_ektacolor_edge",
        "kodak_supra_endura","kodak_portra_endura","fujifilm_crystal_archive_typeii","kodak_2383","kodak_2393"};
    // Generated profile coefficients, in packet order: same-layer RGB, R->GB, G->RB, B->RG.
    private static final float[][] SLIDE_DIR={
        {.12f,.08f,.06f,.12f,.06f,.08f,.06f,.06f,.06f},
        {.12f,.08f,.06f,.12f,.06f,.08f,.06f,.06f,.06f},
        {.108f,.072f,.054f,.108f,.054f,.072f,.054f,.054f,.054f},
        {.156f,.104f,.078f,.156f,.078f,.104f,.078f,.078f,.078f}};
    private static int index(String[] values,String key){
        for(int i=0;i<values.length;i++)if(values[i].equals(key))return i;
        return -1;
    }
    /** P487: a stock with its own measured profile in the spectral renderer (the advanced controls act only on these). */
    public static boolean measured(String stockId){return index(FILMS,stockId)>=0;}
    public static boolean supports(FilmSim.Settings s){
        return s!=null&&s.stock!=null&&s.print!=null&&index(FILMS,s.stock.id)>=0&&index(PAPERS,s.print.id)>=0;
    }
    public static FilmReferenceParameters from(FilmSim.Settings s,boolean p3,int seed){
        if(!supports(s))throw new IllegalArgumentException("Unsupported measured film profile");
        FilmReferenceParameters p=new FilmReferenceParameters();
        p.integer(0,1).integer(4,s.stock.reversal()?1:0).integer(20,p3?18:17)
            .integer(56,index(FILMS,s.stock.id)).integer(60,index(PAPERS,s.print.id))
            .real(84,s.exposure).real(96,s.printEv).real(124,(float)Math.pow(2,s.tone))
            .real(144,s.printM).real(148,s.printY).real(196,s.couplers)
            .integer(352,seed);
        int film=index(FILMS,s.stock.id);
        if(film>=16)for(int i=0;i<9;i++)p.real(220+4*i,SLIDE_DIR[film-16][i]);
        boolean physical=s.format.longMm>0;
        int format;
        switch(s.format.id){
            case "8mm":format=0;break;
            case "super8":format=1;break;
            case "16mm":format=2;break;
            case "super16":format=3;break;
            case "super35":format=5;break;
            case "65mm":format=6;break;
            case "imax70":format=7;break;
            default:format=4;
        }
        p.integer(264,format).real(648,s.format.id.equals("645")||s.format.id.equals("67")?s.format.longMm:0f);
        p.integer(256,physical&&s.grain>0?1:0).real(268,s.grain);
        p.integer(436,physical&&s.halation>0?1:0).real(440,s.halation).real(448,s.halation);
        p.integer(580,physical&&s.softness>0?1:0)
            .real(612,s.softness>0?60f/s.softness:0f).real(620,0f);
        int diffusion=index(FilmSim.DIFFUSIONS,s.diffusion)-1;
        p.integer(492,physical&&diffusion>=0&&s.diffusionGrade>0?1:0)
            .integer(496,Math.max(0,diffusion)).real(500,s.diffusionGrade);
        // P487: the page's DIR / halation / development / spectral-model controls (upstream defaults when untouched)
        FilmAdvanced a=s.advanced;
        p.integer(12,a.rgbToRawMethod()).integer(100,a.pushMode).real(104,a.get(FilmAdvanced.PUSH))
            .real(112,a.get(FilmAdvanced.BLEACH)).real(116,a.get(FilmAdvanced.LEUCO))
            .real(124,(float)Math.pow(2,s.tone)*a.get(FilmAdvanced.DEV_GAMMA))
            .real(200,a.get(FilmAdvanced.DIR_SPREAD)).real(204,a.get(FilmAdvanced.DIR_TAIL)).real(208,a.get(FilmAdvanced.DIR_TAIL_WEIGHT))
            .real(212,a.get(FilmAdvanced.DIR_SAME)).real(216,a.get(FilmAdvanced.DIR_INTER))
            .real(440,s.halation*a.get(FilmAdvanced.SCATTER)).real(444,a.get(FilmAdvanced.SCATTER_SIZE))
            .real(448,s.halation*a.get(FilmAdvanced.HALO)).real(452,a.get(FilmAdvanced.HALO_SIZE))
            .real(480,a.get(FilmAdvanced.BOOST)).real(484,a.get(FilmAdvanced.BOOST_RANGE)).real(488,a.get(FilmAdvanced.PROTECT));
        for(int i=0;i<9;i++)if(Float.isFinite(a.gamma(i)))p.real(220+4*i,a.gamma(i));
        if(!physical)p.real(200,0f).real(204,0f);
        return p;
    }
    /** P487: the stock's own DIR gammas in packet order (offsets 220 .. 252): spektrafilm's defaults, the slides' generated ones. */
    public static float[] dirGamma(String stockId){
        int film=index(FILMS,stockId);
        if(film>=16)return SLIDE_DIR[film-16].clone();
        FilmReferenceParameters p=new FilmReferenceParameters();
        float[] out=new float[9];
        for(int i=0;i<9;i++)out[i]=p.data.getFloat(220+4*i);
        return out;
    }
    public FilmReferenceParameters integer(int offset,int value){
        check(offset);data.putInt(offset,value);return this;
    }
    public FilmReferenceParameters real(int offset,float value){
        check(offset);if(!Float.isFinite(value))throw new IllegalArgumentException("Nonfinite film parameter");
        data.putFloat(offset,value);return this;
    }
    private static void check(int offset){
        if(offset<0||offset>BYTES-4||(offset&3)!=0)throw new IllegalArgumentException("Film parameter offset");
    }
    public ByteBuffer buffer(){ByteBuffer copy=data.duplicate().order(ByteOrder.LITTLE_ENDIAN);copy.clear();return copy;}
}
