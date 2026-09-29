# NewPipeExtractor runs YouTube's player JavaScript through Rhino and loads classes reflectively.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.mozilla.javascript.engine.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.re2j.**

# kotlinx.serialization (backup file format)
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** { *; }

# 1.x data import: these classes are read by Java deserialization, which needs the original
# names, fields and serialVersionUIDs.
-keep class com.palmerintech.firetube.models.** { *; }
