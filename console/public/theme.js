// Blokující skript před CSS: žádné probliknutí, žádný inline skript ani CSP výjimka.
try {
  var theme = localStorage.getItem('arz-theme');
  document.documentElement.dataset.theme = theme === 'light' || theme === 'dark' ? theme : 'system';
} catch (_) { document.documentElement.dataset.theme = 'system'; }
