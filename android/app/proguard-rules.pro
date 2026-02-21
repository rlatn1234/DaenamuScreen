# Bambu MQTT / Paho MQTT
-keep class org.eclipse.paho.** { *; }
-dontwarn org.eclipse.paho.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# App data models
-keep class dev.kimsu.daenamutouchphone.data.model.** { *; }
-keep class dev.kimsu.daenamutouchphone.network.** { *; }
