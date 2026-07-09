# Keep TensorFlow Lite classes (they are referenced via JNI / reflection).
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
