# Keep the entire app package: tiny codebase, includes the WebView JS bridge
# (DshNative via addJavascriptInterface) and shell/proot orchestration where
# reflective access surprises are not worth the bytes.
-keep class com.dshmobile.app.** { *; }

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
