# SCAMERA shrinking (R8). Only what reflection, JNI, GSON, an external launch or a theme reaches by name is listed here; layouts,
# the manifest, navigation graphs and preference XML get their rules from aapt2. P662: the shipped (debug) variant is shrunk with
# these rules, without renaming (-dontobfuscate: stack traces, logs, the settings keys made of class names and the JNI names stay).
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# The settings annotations and the classes that read them by reflection. A class with fields annotated @Tunable / @SensorConfig
# keeps them (their names are the preference keys) and keeps itself (TunableInjector / SensorConfigInjector key them by the
# class's simple name: it must not be merged into another class). P662: was "-keep class * { ... }", which kept every class.
-keep class com.scamera.app.settings.annotations.** { *; }
-keepclasseswithmembers class * { @com.scamera.app.settings.annotations.Tunable <fields>; }
-keepclasseswithmembers class * { @com.scamera.app.settings.annotations.SensorConfig <fields>; }
-keepclassmembers class * { @com.scamera.app.settings.annotations.** <fields>; }
-keep class com.scamera.app.settings.TunableInjector,
            com.scamera.app.settings.TunablePreferenceGenerator,
            com.scamera.app.settings.TunablePreferenceGenerator$*,
            com.scamera.app.settings.TunableSettingsManager,
            com.scamera.app.settings.SettingsManagerExtensions,
            com.scamera.app.settings.TunableKeyManager,
            com.scamera.app.settings.SensorConfigInjector,
            com.scamera.app.settings.SensorConfigPreferenceGenerator,
            com.scamera.app.settings.SensorConfigPreferenceGenerator$*,
            com.scamera.app.settings.SensorConfigRegistry,
            com.scamera.app.settings.TunableRegistry,
            com.scamera.app.settings.FieldSpec,
            com.scamera.app.settings.SessionKeys { *; }
-keep class com.scamera.app.ui.settings.custompreferences.TunableSeekBarPreference,
            com.scamera.app.ui.settings.custompreferences.TunableKeyPreference,
            com.scamera.app.ui.settings.custompreferences.TunableKeyDialog { *; }

# GSON models (fields by name or @SerializedName, made without a constructor call): vendor tag lists and the lens table.
-keep class com.scamera.app.api.VendorTagUtils,
            com.scamera.app.api.VendorTagUtils$TunableKey { *; }
-keep class com.scamera.app.ui.camera.data.CameraLensData { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class * extends com.google.gson.reflect.TypeToken

# The lab's parameter record: every instance field is written to and read from its JSON by reflection (ScamLabParams).
-keepclassmembers class com.scamera.app.processing.render.Parameters { <fields>; }

# Serializable (SettingsSearchFragment.Entry in a Bundle): the standard members.
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
    <fields>;
}

# Classes a library instantiates from a Class object (newInstance): the gallery's region decoder (ImageAdapter ->
# SubsamplingScaleImageView.setRegionDecoderClass; R8 saw no "new" and removed its constructor and methods).
-keep class * implements com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder { <init>(...); *; }
-keep class * implements com.davemorrissey.labs.subscaleview.decoder.ImageDecoder { <init>(...); *; }

# The su launcher starts the merge job as "app_process64 /system/bin <ScamNeuralWorker> ..." (ScamNeuralClient): its main.
-keep class com.scamera.app.processing.opengl.postpipeline.ScamNeuralWorker { *; }

# JNI: every class with native methods keeps its name and those methods (the C++ side is bound by Java_<class>_<method>).
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
# The OCR runtime's native code calls back into its Java classes (TessBaseAPI.onProgressValues, fields read from C++); its AAR
# carries no rules.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# The lab activity is kept whole; the stock AE keeps its JNI-bound natives.
-keep class com.scamera.app.ui.settings.ScamActivity { *; }
-keep class com.scamera.app.capture.ScamStockAe { native <methods>; }

# P582: the base theme names it in viewInflaterClass; AppCompat makes it by reflection.
-keep class com.scamera.app.ui.style.ConceptViewInflater { <init>(); }

# A commons-lang class a dependency refers to but never loads here.
-dontwarn org.apache.commons.lang.functor.Predicate
