# sherpa-onnx's native code reads its Kotlin config classes field by field
# and builds its results by name, and its library brings no rules of its own.
-keep class com.k2fsa.sherpa.onnx.** { *; }
