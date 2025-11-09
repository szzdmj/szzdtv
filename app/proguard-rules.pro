# 通用混淆补充
-dontwarn javax.annotation.**
-dontwarn org.intellij.lang.annotations.**

# 保留 Kotlin 元数据
-keep class kotlin.Metadata { *; }

# 保留 Leanback fragment（避免反射丢失）
-keep public class * extends androidx.leanback.app.BrowseSupportFragment
-keep public class * extends androidx.leanback.app.DetailsSupportFragment

# 保留 ViewBinding（R8 一般能自动处理，仅保险）
-keep class **Binding { *; }

# 若使用 Gson 反射的模型，可按包名保留字段
# -keep class com.example.tvapp.model.** { *; }
