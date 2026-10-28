# R8 混淆规则。release 构建会重命名/删除未被引用的代码，
# 但通过 addJavascriptInterface 暴露给网页 JS 的方法是「从 JS 侧按名字调用」的，R8 看不到调用点，必须保留。
-keepclassmembers class com.yuanfeng.webshell.RefreshGuard {
    @android.webkit.JavascriptInterface <methods>;
}
