package me.rerere.rikkahub.browser

/**
 * 浏览器自动化 JS 注入 — 对标 OpenMinis BrowserUseJS
 *
 * 提供一组封装好的 JavaScript 代码片段，用于 AI 驱动浏览器操作。
 * 所有函数生成可直接通过 evaluateJavascript 执行的 JS 代码。
 */
object BrowserUseJS {

    fun jsQuote(s: String): String = buildString {
        append('\'')
        for (c in s) {
            when (c) {
                '\'' -> append("\\'")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('\'')
    }

    fun click(selector: String): String = """
        (function() {
            var el = document.querySelector(${jsQuote(selector)});
            if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}'});
            if (el.disabled) return JSON.stringify({error: 'Element is disabled', tag: el.tagName});
            el.scrollIntoView({behavior: 'instant', block: 'center'});
            var bOpts = {bubbles: true, cancelable: true, view: window};
            var nbOpts = {bubbles: false, cancelable: true, view: window};
            el.dispatchEvent(new MouseEvent('mouseover', bOpts));
            el.dispatchEvent(new MouseEvent('mouseenter', nbOpts));
            el.dispatchEvent(new MouseEvent('mousemove', bOpts));
            el.dispatchEvent(new MouseEvent('mousedown', bOpts));
            el.dispatchEvent(new MouseEvent('mouseup', bOpts));
            el.click();
            el.dispatchEvent(new MouseEvent('mouseleave', nbOpts));
            el.dispatchEvent(new MouseEvent('mouseout', bOpts));
            return JSON.stringify({clicked: true, tag: el.tagName, text: (el.innerText || '').substring(0, 100)});
        })();
    """.trimIndent()

    fun clickCoordinate(x: Int, y: Int): String = """
        (function() {
            var el = document.elementFromPoint($x, $y);
            if (!el) return JSON.stringify({error: 'No element at ($x, $y)'});
            if (el.disabled) return JSON.stringify({error: 'Element is disabled', tag: el.tagName, x: $x, y: $y});
            var bOpts = {bubbles: true, cancelable: true, view: window};
            var nbOpts = {bubbles: false, cancelable: true, view: window};
            el.dispatchEvent(new MouseEvent('mouseover', bOpts));
            el.dispatchEvent(new MouseEvent('mouseenter', nbOpts));
            el.dispatchEvent(new MouseEvent('mousemove', bOpts));
            el.dispatchEvent(new MouseEvent('mousedown', bOpts));
            el.dispatchEvent(new MouseEvent('mouseup', bOpts));
            el.click();
            el.dispatchEvent(new MouseEvent('mouseleave', nbOpts));
            el.dispatchEvent(new MouseEvent('mouseout', bOpts));
            return JSON.stringify({clicked: true, tag: el.tagName, x: $x, y: $y, text: (el.innerText || '').substring(0, 100)});
        })();
    """.trimIndent()

    fun type(selector: String, text: String): String = """
        (function() {
            var el = document.querySelector(${jsQuote(selector)});
            if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}'});
            el.focus();
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
                var nativeSetter = Object.getOwnPropertyDescriptor(
                    el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype, 'value'
                );
                if (nativeSetter && nativeSetter.set) {
                    nativeSetter.set.call(el, ${jsQuote(text)});
                } else {
                    el.value = ${jsQuote(text)};
                }
            } else {
                el.innerText = ${jsQuote(text)};
            }
            var chars = ${jsQuote(text)};
            for (var i = 0; i < chars.length; i++) {
                var c = chars[i];
                el.dispatchEvent(new KeyboardEvent('keydown', {key: c, bubbles: true}));
                el.dispatchEvent(new KeyboardEvent('keypress', {key: c, bubbles: true}));
                el.dispatchEvent(new InputEvent('input', {data: c, inputType: 'insertText', bubbles: true}));
                el.dispatchEvent(new KeyboardEvent('keyup', {key: c, bubbles: true}));
            }
            el.dispatchEvent(new Event('change', {bubbles: true}));
            try {
                if (window.angular) {
                    var ngEl = window.angular.element(el);
                    var scope = ngEl.scope() || (ngEl.injector && ngEl.injector().get('${'$'}rootScope'));
                    if (scope && !scope.${'$'}${'$'}phase) scope.${'$'}apply();
                }
            } catch(e) {}
            try {
                if (el.__vue__) el.__vue__.${'$'}forceUpdate();
                if (el._vei || el.__vueParentComponent) el.dispatchEvent(new Event('input', {bubbles: true}));
            } catch(e) {}
            if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable) {
                el.dispatchEvent(new FocusEvent('blur', {bubbles: true, relatedTarget: null}));
                el.dispatchEvent(new FocusEvent('focusout', {bubbles: true, relatedTarget: null}));
            }
            return JSON.stringify({typed: true, selector: '${jsQuote(selector)}', length: chars.length});
        })();
    """.trimIndent()

    fun getText(selector: String? = null): String {
        val sel = selector ?: "body"
        return """
            (function() {
                var el = document.querySelector(${jsQuote(sel)});
                if (!el) return JSON.stringify({error: 'Element not found'});
                return JSON.stringify({text: el.innerText.trim().substring(0, 10000)});
            })();
        """.trimIndent()
    }

    fun scroll(direction: String, amount: Int = 500, selector: String? = null): String {
        val pixels = if (direction == "down") amount else -amount
        val dir = direction
        return if (selector != null) {
            """
                (function() {
                    var el = document.querySelector(${jsQuote(selector)});
                    if (!el) return JSON.stringify({error: 'Element not found: ${jsQuote(selector)}'});
                    el.scrollBy(0, $pixels);
                    return JSON.stringify({scrolled: true, element: '${jsQuote(selector)}', direction: '$dir', amount: $amount, scrollTop: el.scrollTop});
                })();
            """.trimIndent()
        } else {
            """
                (function() {
                    var beforeY = window.scrollY;
                    window.scrollBy(0, $pixels);
                    if (window.scrollY !== beforeY) {
                        var sh = document.documentElement.scrollHeight || document.body.scrollHeight;
                        return JSON.stringify({scrolled: true, element: 'window', direction: '$dir', amount: $amount, scrollY: window.scrollY, scrollHeight: sh, viewportHeight: window.innerHeight});
                    }
                    var best = null;
                    var bestArea = 0;
                    function walk(el, depth) {
                        if (depth > 10) return;
                        var children = el.children;
                        for (var i = 0; i < children.length; i++) {
                            var child = children[i];
                            var st = window.getComputedStyle(child);
                            var oy = st.overflowY;
                            if ((oy === 'auto' || oy === 'scroll') && child.scrollHeight > child.clientHeight + 5) {
                                var area = child.clientWidth * child.clientHeight;
                                if (area > bestArea) { best = child; bestArea = area; }
                            }
                            walk(child, depth + 1);
                        }
                    }
                    walk(document.body, 0);
                    if (best) {
                        best.scrollBy(0, $pixels);
                        return JSON.stringify({scrolled: true, element: best.tagName.toLowerCase(), direction: '$dir', amount: $amount, scrollTop: best.scrollTop, scrollHeight: best.scrollHeight, clientHeight: best.clientHeight});
                    }
                    document.documentElement.scrollTop += $pixels;
                    return JSON.stringify({scrolled: true, element: 'document.documentElement', direction: '$dir', amount: $amount, scrollY: document.documentElement.scrollTop});
                })();
            """.trimIndent()
        }
    }

    fun hover(selector: String): String = """
        (function() {
            var el = document.querySelector(${jsQuote(selector)});
            if (!el) return JSON.stringify({error: 'Element not found'});
            el.dispatchEvent(new MouseEvent('mouseover', {bubbles: true}));
            el.dispatchEvent(new MouseEvent('mouseenter', {bubbles: true}));
            return JSON.stringify({success: true});
        })();
    """.trimIndent()

    fun findElements(selector: String): String = """
        (function() {
            var els = document.querySelectorAll(${jsQuote(selector)});
            var results = [];
            var limit = Math.min(els.length, 50);
            var scrollX = window.scrollX || window.pageXOffset || 0;
            var scrollY = window.scrollY || window.pageYOffset || 0;
            for (var i = 0; i < limit; i++) {
                var el = els[i];
                var rect = el.getBoundingClientRect();
                results.push({
                    index: i, tag: el.tagName, id: el.id || null,
                    className: (typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 2).join(' ') : null),
                    text: (el.innerText || '').substring(0, 100),
                    href: el.href || null,
                    rect: {x: Math.round(rect.x), y: Math.round(rect.y), width: Math.round(rect.width), height: Math.round(rect.height), pageX: Math.round(rect.x + scrollX), pageY: Math.round(rect.y + scrollY), visible: rect.width > 0 && rect.height > 0 && rect.top < window.innerHeight && rect.bottom > 0}
                });
            }
            return JSON.stringify({count: els.length, shown: limit, elements: results});
        })();
    """.trimIndent()

    fun getPageInfo(): String = """
        (function() {
            return JSON.stringify({
                url: window.location.href,
                title: document.title,
                scrollY: window.scrollY,
                scrollHeight: document.body.scrollHeight,
                viewportWidth: window.innerWidth,
                viewportHeight: window.innerHeight,
                readyState: document.readyState,
                forms: document.forms.length,
                links: document.links.length,
                images: document.images.length
            });
        })();
    """.trimIndent()

    fun getReadable(): String = """
        (function() {
            var candidateSelectors = [
                'article', '[role="main"]', 'main', '.post-content',
                '.article-body', '.entry-content', '#content', '.content'
            ];
            var el = null;
            var matchedSelector = null;
            for (var i = 0; i < candidateSelectors.length; i++) {
                var found = document.querySelector(candidateSelectors[i]);
                if (found && window.getComputedStyle(found).display !== 'none' && (found.innerText || '').length > 0) {
                    el = found; matchedSelector = candidateSelectors[i]; break;
                }
            }
            if (!el) { el = document.body; matchedSelector = 'document.body (fallback)'; }
            var title = document.title || '';
            var innerTextVal = el.innerText || '';
            var text = innerTextVal.replace(/\s+/g, ' ').trim().substring(0, 15000);
            return JSON.stringify({title: title, text: text, length: text.length, source: matchedSelector});
        })();
    """.trimIndent()

    fun getBackbone(maxDepth: Int = 5): String = """
        (function() {
            var MAX_DEPTH = $maxDepth;
            function getNodeInfo(node, depth) {
                if (!node || depth > MAX_DEPTH) return null;
                if (node.nodeType !== 1) return null;
                var tag = node.tagName.toLowerCase();
                if (tag === 'script' || tag === 'style' || tag === 'noscript') return null;
                var children = [];
                for (var i = 0; i < node.children.length; i++) {
                    var child = getNodeInfo(node.children[i], depth + 1);
                    if (child) children.push(child);
                }
                var info = {tag: tag};
                if (node.id) info.id = node.id;
                if (node.className && typeof node.className === 'string') {
                    var cls = node.className.trim().split(/\s+/).slice(0, 2).join(' ');
                    if (cls) info.cls = cls;
                }
                if (children.length > 0) info.children = children;
                if (children.length === 0 && node.textContent) {
                    var t = node.textContent.trim().substring(0, 50);
                    if (t) info.text = t;
                }
                return info;
            }
            var backbone = getNodeInfo(document.body, 0);
            return JSON.stringify(backbone ? {backbone: backbone} : {error: 'empty'});
        })();
    """.trimIndent()

    /**
     * 异步 fetch — 通过 JS bridge 返回结果（Promise 由 __rikkahub__ 桥接）。
     * 调用方需使用 BrowserSession.evaluateAsyncJs 执行。
     */
    fun fetch(url: String): String = """
        (async function() {
            try {
                const resp = await fetch(${jsQuote(url)});
                const buf = await resp.arrayBuffer();
                const bytes = new Uint8Array(buf);
                let binary = '';
                for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
                const b64 = btoa(binary);
                return JSON.stringify({
                    base64: b64,
                    contentType: resp.headers.get('content-type') || '',
                    contentDisposition: resp.headers.get('content-disposition') || '',
                    status: resp.status,
                    url: resp.url,
                    size: bytes.length
                });
            } catch(e) {
                return JSON.stringify({error: e.message});
            }
        })()
    """.trimIndent()
}
