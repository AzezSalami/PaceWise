# kotlinx.serialization: keep generated serializers for the shared models
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class app.pacewise.**$$serializer { *; }
-keepclassmembers class app.pacewise.** { *** Companion; }
-keepclasseswithmembers class app.pacewise.** { kotlinx.serialization.KSerializer serializer(...); }
