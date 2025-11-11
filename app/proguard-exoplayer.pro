# Media3 保留与警告抑制
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }

# 若使用 ffmpeg 扩展，可视需要添加：
# -dontwarn org.ffmpeg.**
# -keep class org.ffmpeg.** { *; }
