// 注入示例：页面加载完成后执行（每个页面一次）。
// 给 <html> 打一个标记，inject.css 里的规则只在 App 内生效（方便和普通浏览器区分）。
document.documentElement.setAttribute('data-webshell', '1');
