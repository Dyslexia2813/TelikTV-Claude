/* Added by TelikTV to every page and player frame. The box's built-in browser (Chrome 52, 2016)
   lacks newer JavaScript functions that the site uses. ES5 only. */
(function () {
  var S = String.prototype, A = Array.prototype, O = Object, w = window;
  function d(o, n, f) {
    if (!o || o[n]) return;
    try { O.defineProperty(o, n, { value: f, configurable: true, writable: true }); } catch (e) { o[n] = f; }
  }
  function pad(s, len, f, start) {
    s = String(s); len = len >> 0; f = f === undefined ? ' ' : String(f);
    if (s.length >= len || !f) return s;
    var n = len - s.length;
    while (f.length < n) f += f;
    f = f.slice(0, n);
    return start ? f + s : s + f;
  }
  function at(i) { i = Math.trunc(i) || 0; if (i < 0) i += this.length; return this[i]; }
  d(S, 'padStart', function (l, f) { return pad(this, l, f, 1); });
  d(S, 'padEnd', function (l, f) { return pad(this, l, f, 0); });
  d(S, 'trimStart', S.trimLeft);
  d(S, 'trimEnd', S.trimRight);
  d(S, 'replaceAll', function (a, b) {
    return typeof a === 'string' ? this.split(a).join(typeof b === 'function' ? b(a) : b) : this.replace(a, b);
  });
  d(S, 'at', at);
  d(A, 'at', at);
  d(A, 'flat', function (depth) {
    depth = depth === undefined ? 1 : Number(depth);
    var out = [];
    (function f(arr, dp) {
      for (var i = 0; i < arr.length; i++) {
        if (Array.isArray(arr[i]) && dp > 0) f(arr[i], dp - 1); else out.push(arr[i]);
      }
    })(this, depth);
    return out;
  });
  d(A, 'flatMap', function (fn, t) { return A.flat.call(A.map.call(this, fn, t), 1); });
  d(A, 'findLast', function (fn, t) { for (var i = this.length - 1; i >= 0; i--) if (fn.call(t, this[i], i, this)) return this[i]; });
  d(A, 'findLastIndex', function (fn, t) { for (var i = this.length - 1; i >= 0; i--) if (fn.call(t, this[i], i, this)) return i; return -1; });
  d(O, 'entries', function (o) { return O.keys(o).map(function (k) { return [k, o[k]]; }); });
  d(O, 'values', function (o) { return O.keys(o).map(function (k) { return o[k]; }); });
  d(O, 'fromEntries', function (it) { var r = {}; Array.from(it).forEach(function (p) { r[p[0]] = p[1]; }); return r; });
  d(O, 'getOwnPropertyDescriptors', function (o) {
    var r = {}; O.getOwnPropertyNames(o).forEach(function (k) { r[k] = O.getOwnPropertyDescriptor(o, k); }); return r;
  });
  d(O, 'hasOwn', function (o, k) { return O.prototype.hasOwnProperty.call(o, k); });
  if (w.Promise) {
    d(Promise.prototype, 'finally', function (f) {
      var C = Promise;
      return this.then(function (v) { return C.resolve(f()).then(function () { return v; }); },
        function (e) { return C.resolve(f()).then(function () { throw e; }); });
    });
    d(Promise, 'allSettled', function (ps) {
      return Promise.all(Array.from(ps).map(function (p) {
        return Promise.resolve(p).then(function (v) { return { status: 'fulfilled', value: v }; },
          function (e) { return { status: 'rejected', reason: e }; });
      }));
    });
    d(Promise, 'any', function (ps) {
      return new Promise(function (res, rej) {
        var arr = Array.from(ps), left = arr.length;
        if (!left) rej(new Error('All promises were rejected'));
        arr.forEach(function (p) { Promise.resolve(p).then(res, function () { if (--left === 0) rej(new Error('All promises were rejected')); }); });
      });
    });
  }
  if (typeof w.globalThis === 'undefined') w.globalThis = w;
  if (!w.queueMicrotask) w.queueMicrotask = function (f) { Promise.resolve().then(f); };
  if (w.NodeList) d(NodeList.prototype, 'forEach', A.forEach);
  if (w.Element) {
    var E = w.Element.prototype;
    d(E, 'closest', function (s) { var el = this; while (el && el.nodeType === 1) { if (el.matches(s)) return el; el = el.parentElement; } return null; });
    d(E, 'remove', function () { if (this.parentNode) this.parentNode.removeChild(this); });
    d(E, 'toggleAttribute', function (n, f) {
      var on = f === undefined ? !this.hasAttribute(n) : !!f; if (on) this.setAttribute(n, ''); else this.removeAttribute(n); return on;
    });
    d(E, 'append', function () { for (var i = 0; i < arguments.length; i++) { var a = arguments[i]; this.appendChild(typeof a === 'string' ? document.createTextNode(a) : a); } });
    d(E, 'prepend', function () { var f = this.firstChild; for (var i = 0; i < arguments.length; i++) { var a = arguments[i]; this.insertBefore(typeof a === 'string' ? document.createTextNode(a) : a, f); } });
  }
})();
