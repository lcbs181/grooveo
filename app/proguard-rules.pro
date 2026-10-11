# R8 for release: shrinks the libraries (icons, Compose, NewPipe, Rhino ...) while
# keeping the app's own code exactly as written. Kept whole because Gson reads and
# writes our data classes by field name (backups, EQ profiles, settings, DTOs) and
# Room/Hilt look up generated classes by name; renaming them would break stored data.
-keep class dev.schlubbe.musicagent.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, SourceFile, LineNumberTable

# NewPipeExtractor runs YouTube's player JavaScript in Rhino, which loads classes
# reflectively; the time-ago patterns are looked up by name per language.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**

# Gson TypeToken subclasses (generic type capture)
-keep class * extends com.google.gson.reflect.TypeToken
