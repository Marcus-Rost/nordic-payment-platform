# Keep kotlinx.serialization-generated companion objects so reflection-based
# polymorphic deserialisation continues to work after R8 minification.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class com.jrpay.client.**$$serializer { *; }
-keepclassmembers class com.jrpay.client.** {
    *** Companion;
}
-keepclasseswithmembers class com.jrpay.client.** {
    kotlinx.serialization.KSerializer serializer(...);
}
