# ORT JNI resolves Java classes and members by name.
-keep class ai.onnxruntime.** { *; }
# ML Kit discovers these registrars with getDeclaredConstructor().newInstance().
-keepclassmembers class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
