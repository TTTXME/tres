package com.scamera.app.processing.parameters;

/** Recovered arithmetic, with explicit guards outside the donor's tested domain. */
public final class FinishMath {
    private FinishMath() {}
    public static double effectiveSamples(double weight,double squareWeight) {
        if(!Double.isFinite(weight+squareWeight) || weight<=0 || squareWeight<=0) return 1;
        return Math.max(1,weight*weight/squareWeight);
    }
    public static double varianceScale(double effectiveSamples,double independentSpatialSamples) {
        if(!Double.isFinite(effectiveSamples) || effectiveSamples<1) effectiveSamples=1;
        if(!Double.isFinite(independentSpatialSamples) || independentSpatialSamples<1) independentSpatialSamples=1;
        return 1/(effectiveSamples*independentSpatialSamples);
    }
}
