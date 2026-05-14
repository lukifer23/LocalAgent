-keep class com.localagent.llm.LlamaNative { *; }
-keep class com.localagent.runtime.PtySession { *; }

-keepattributes *Annotation*, InnerClasses, EnclosingMethod
-dontwarn kotlinx.serialization.**
