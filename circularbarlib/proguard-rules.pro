# Release shrinking of the manual panel library. The app reaches the panel through its api package and reads the manual
# values from ManualParamModel, so both keep their public members.
-ignorewarnings
-keep class com.scamera.app.circularbarlib.api.* { public *; }
-keep class com.scamera.app.circularbarlib.control.ManualParamModel { public *; }
