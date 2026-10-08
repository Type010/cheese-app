# 目前没有反射/序列化需要保留的类；这里只留一条兜底，
# 防止 release 构建因为三方库的注解告警失败。
-dontwarn org.jetbrains.annotations.**
-keep class com.cheese.app.BuildInfo { *; }
