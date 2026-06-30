package co.cben.dev.aiinlinereview

import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import java.util.UUID

/**
 * A localhost-only HTTP server for the web-review flow. Serves an overlay script and receives
 * screenshot + comment captures from a webpage, turning each into a context-free session note.
 */
@Service(Service.Level.PROJECT)
class ReviewWebServer(private val project: Project) : Disposable {

    private val gson = Gson()

    @Volatile
    private var server: HttpServer? = null

    var port: Int = 0
        private set

    fun start() {
        if (server != null) return
        for (candidate in 63420..63440) {
            try {
                val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", candidate), 0)
                httpServer.createContext("/inject.js", InjectHandler())
                httpServer.createContext("/note", NoteHandler())
                httpServer.createContext("/", RootHandler())
                httpServer.executor = null
                httpServer.start()
                server = httpServer
                port = candidate
                LOG.info("AI Inline Review web server listening on http://localhost:$candidate")
                return
            } catch (_: IOException) {
                // port busy, try next
            }
        }
        LOG.warn("AI Inline Review web server could not bind a port")
    }

    /**
     * Inline bookmarklet: the whole overlay is encoded into the javascript: URL, so no external
     * script is loaded (dodges strict `script-src` CSP, e.g. Firefox). Connecting to localhost is
     * still subject to the page's `connect-src`.
     */
    fun bookmarklet(): String {
        val js = INJECT_JS.replace("%BASE%", "http://localhost:$port")
        return "javascript:" + java.net.URLEncoder.encode(js, "UTF-8").replace("+", "%20")
    }

    override fun dispose() {
        server?.stop(0)
        server = null
    }

    private fun cors(exchange: HttpExchange) {
        val origin = exchange.requestHeaders.getFirst("Origin")
        exchange.responseHeaders.add("Access-Control-Allow-Origin", origin ?: "*")
        exchange.responseHeaders.add("Vary", "Origin")
        exchange.responseHeaders.add("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        // Reflect requested headers so injected tracing headers (baggage, sentry-trace, ...) pass.
        val requested = exchange.requestHeaders.getFirst("Access-Control-Request-Headers")
        exchange.responseHeaders.add("Access-Control-Allow-Headers", requested ?: "*")
        exchange.responseHeaders.add("Access-Control-Max-Age", "600")
    }

    private fun send(exchange: HttpExchange, code: Int, body: String, contentType: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private inner class InjectHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            cors(exchange)
            send(exchange, 200, INJECT_JS.replace("%BASE%", "http://localhost:$port"), "application/javascript; charset=utf-8")
        }
    }

    private inner class RootHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            cors(exchange)
            val html = "<!doctype html><meta charset=utf-8><h2>AI Inline Review</h2>" +
                "<p>Bookmarklet (drag to bookmarks bar or use as a bookmark URL):</p>" +
                "<p><a href=\"${bookmarklet()}\">AI Inline Review</a></p>"
            send(exchange, 200, html, "text/html; charset=utf-8")
        }
    }

    private inner class NoteHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            cors(exchange)
            if (exchange.requestMethod == "OPTIONS") {
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
                return
            }
            if (exchange.requestMethod != "POST") {
                send(exchange, 405, "method not allowed", "text/plain")
                return
            }
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val data = runCatching { gson.fromJson(body, WebNote::class.java) }.getOrNull()
            if (data == null) {
                send(exchange, 400, "bad json", "text/plain")
                return
            }
            val relativeImage = data.image?.let { saveImage(it) }
            ApplicationManager.getApplication().invokeLater { createNote(data, relativeImage) }
            send(exchange, 200, "ok", "text/plain")
        }
    }

    private fun saveImage(dataUrl: String): String? {
        val comma = dataUrl.indexOf(',')
        val base64 = if (comma >= 0) dataUrl.substring(comma + 1) else dataUrl
        val bytes = runCatching { Base64.getDecoder().decode(base64) }.getOrNull() ?: return null
        val base = project.basePath ?: return null
        val dir = Paths.get(base, ".claude", "review", "assets")
        Files.createDirectories(dir)
        val name = "${UUID.randomUUID()}.png"
        Files.write(dir.resolve(name), bytes)
        return ".claude/review/assets/$name"
    }

    private fun createNote(data: WebNote, relativeImage: String?) {
        val store = ReviewStore.getInstance(project)
        val markdown = buildString {
            data.comment?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
            data.url?.takeIf { it.isNotBlank() }?.let { append("[").append(it).append("](").append(it).append(")\n\n") }
            relativeImage?.let { append("![](").append(it).append(")") }
        }.trim()
        store.add(
            ReviewComment().apply {
                id = UUID.randomUUID().toString()
                filePath = ""
                sessionId = store.activeSessionId
                author = ReviewUi.currentAuthor
                createdAt = System.currentTimeMillis()
                comment = markdown.ifBlank { "(web capture)" }
                if (relativeImage != null) attachments = mutableListOf(relativeImage)
            },
        )
    }

    private class WebNote {
        @JvmField var url: String? = null
        @JvmField var comment: String? = null
        @JvmField var image: String? = null
    }

    companion object {
        private val LOG = logger<ReviewWebServer>()

        fun getInstance(project: Project): ReviewWebServer = project.getService(ReviewWebServer::class.java)

        // Overlay served at /inject.js. Avoids '$' (Kotlin interpolation) and backticks on purpose.
        // A floating button opens a modal that captures the current tab, lets you annotate it with a
        // pen, add a comment, and send it. Adapted from the user's feedback-widget script.
        private val INJECT_JS = """
            (function () {
              if (window.__claudeReview) { return; }
              window.__claudeReview = true;
              var BASE = "%BASE%";
              var open = false;

              function el(tag, css, props) {
                var n = document.createElement(tag);
                if (css) { n.style.cssText = css; }
                if (props) { Object.keys(props).forEach(function (k) { n[k] = props[k]; }); }
                return n;
              }

              function toast(msg, err) {
                var t = el("div", "position:fixed;bottom:20px;left:50%;transform:translateX(-50%);z-index:2147483647;padding:10px 16px;border-radius:8px;color:#fff;font:14px system-ui,sans-serif;box-shadow:0 4px 16px rgba(0,0,0,.3);background:" + (err ? "#c0392b" : "#1e8449"), { textContent: msg });
                document.body.appendChild(t);
                setTimeout(function () { t.remove(); }, 3000);
              }

              function capture() {
                if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) { return Promise.resolve(null); }
                return navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 8, width: { ideal: 3840 }, height: { ideal: 2160 } }, audio: false, preferCurrentTab: true, selfBrowserSurface: "include" }).then(function (stream) {
                  var v = document.createElement("video"); v.muted = true; v.playsInline = true; v.srcObject = stream;
                  return new Promise(function (resolve) {
                    function finish(d) { stream.getTracks().forEach(function (t) { t.stop(); }); resolve(d); }
                    v.onloadedmetadata = function () {
                      v.play().catch(function () {});
                      var tries = 0;
                      (function grab() {
                        var w = v.videoWidth, h = v.videoHeight;
                        if ((!w || !h) && tries++ < 30) { requestAnimationFrame(grab); return; }
                        if (!w || !h) { finish(null); return; }
                        var c = document.createElement("canvas"); c.width = w; c.height = h;
                        c.getContext("2d").drawImage(v, 0, 0, w, h);
                        try { finish(c.toDataURL("image/png")); } catch (e) { finish(null); }
                      })();
                    };
                  });
                }).catch(function () { return null; });
              }

              function send(note, shot) {
                fetch(BASE + "/note", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ url: location.href, comment: note, image: shot || null }) })
                  .then(function (r) { toast(r && r.ok ? "Sent to AI Inline Review" : "Send failed", !(r && r.ok)); })
                  .catch(function (e) { toast("Send failed: " + e, true); });
              }

              function buildOverlay(shot) {
                var ov = el("div", "position:fixed;inset:0;z-index:2147483646;background:rgba(0,0,0,.55);display:flex;align-items:center;justify-content:center;padding:20px");
                var box = el("div", "background:#2b2d30;color:#ddd;border:1px solid #4e525a;border-radius:12px;padding:16px;max-width:820px;width:100%;max-height:90vh;overflow:auto;box-shadow:0 12px 48px rgba(0,0,0,.5);font:13px/1.45 system-ui,sans-serif");
                box.appendChild(el("div", "font-weight:600;font-size:14px;margin-bottom:10px;color:#6ea8fe", { textContent: "AI Inline Review" }));

                var canvas = null;
                if (shot) {
                  var current = "#ee1111";
                  var tools = el("div", "display:flex;gap:6px;align-items:center;margin-bottom:6px;font-size:12px;color:#aaa");
                  tools.appendChild(el("span", "", { textContent: "Pen:" }));
                  ["#ee1111", "#11aa11", "#1155cc", "#ffbb00", "#ffffff"].forEach(function (col) {
                    var b = el("button", "width:18px;height:18px;border-radius:50%;border:2px solid #2b2d30;outline:1px solid #666;cursor:pointer;background:" + col, { type: "button" });
                    b.onclick = function () { current = col; };
                    tools.appendChild(b);
                  });
                  var clearBtn = el("button", "margin-left:auto;padding:2px 8px;border:1px solid #4e525a;background:#3a3d42;color:#ddd;border-radius:5px;cursor:pointer", { type: "button", textContent: "Clear marks" });
                  tools.appendChild(clearBtn);
                  box.appendChild(tools);

                  canvas = el("canvas", "max-width:100%;border:1px solid #4e525a;border-radius:6px;display:block;margin-bottom:10px;cursor:crosshair;touch-action:none");
                  box.appendChild(canvas);

                  var im = new Image();
                  im.onload = function () {
                    // Full-resolution buffer; CSS (max-width:100% + height:auto) scales the display only,
                    // so the exported PNG keeps native quality with the annotations baked in.
                    canvas.width = im.naturalWidth;
                    canvas.height = im.naturalHeight;
                    canvas.style.height = "auto";
                    var ctx = canvas.getContext("2d");
                    ctx.drawImage(im, 0, 0);
                    var pen = Math.max(3, Math.round(canvas.width / 500));
                    var drawing = false, lx = 0, ly = 0;
                    function pos(e) { var r = canvas.getBoundingClientRect(); return { x: (e.clientX - r.left) * (canvas.width / r.width), y: (e.clientY - r.top) * (canvas.height / r.height) }; }
                    canvas.addEventListener("pointerdown", function (e) { drawing = true; var p = pos(e); lx = p.x; ly = p.y; canvas.setPointerCapture(e.pointerId); });
                    canvas.addEventListener("pointermove", function (e) { if (!drawing) { return; } var p = pos(e); ctx.strokeStyle = current; ctx.lineWidth = pen; ctx.lineCap = "round"; ctx.beginPath(); ctx.moveTo(lx, ly); ctx.lineTo(p.x, p.y); ctx.stroke(); lx = p.x; ly = p.y; });
                    canvas.addEventListener("pointerup", function () { drawing = false; });
                    clearBtn.onclick = function () { ctx.drawImage(im, 0, 0); };
                  };
                  im.src = shot;
                } else {
                  box.appendChild(el("div", "margin-bottom:10px;font-size:12px;color:#e08", { textContent: "No screenshot (capture cancelled). You can still send a note." }));
                }

                var ta = el("textarea", "width:100%;box-sizing:border-box;padding:8px;background:#1e1f22;color:#ddd;border:1px solid #4e525a;border-radius:6px;font:inherit", { rows: 3, placeholder: "What to change / adapt on this screen?" });
                box.appendChild(ta);

                var actions = el("div", "margin-top:10px;display:flex;gap:8px;justify-content:flex-end");
                var cancel = el("button", "padding:6px 12px;border:1px solid #4e525a;background:#3a3d42;color:#ddd;border-radius:6px;cursor:pointer", { type: "button", textContent: "Cancel" });
                var sendBtn = el("button", "padding:6px 12px;border:0;background:#3574f0;color:#fff;border-radius:6px;cursor:pointer", { type: "button", textContent: "Send (Ctrl+Enter)" });
                actions.appendChild(cancel); actions.appendChild(sendBtn);
                box.appendChild(actions);

                ov.appendChild(box); document.body.appendChild(ov); ta.focus();

                function close() { open = false; ov.remove(); }
                function submit() {
                  var v = ta.value.trim();
                  var finalShot = canvas ? canvas.toDataURL("image/png") : (shot || null);
                  if (v || finalShot) { send(v, finalShot); }
                  close();
                }
                cancel.onclick = close;
                sendBtn.onclick = submit;
                ov.addEventListener("click", function (e) { if (e.target === ov) { close(); } });
                ta.addEventListener("keydown", function (e) { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { submit(); } if (e.key === "Escape") { close(); } });
              }

              var fab = el("button", "position:fixed;right:18px;bottom:18px;z-index:2147483647;width:46px;height:46px;border-radius:50%;border:0;cursor:pointer;background:#3574f0;color:#fff;font-size:18px;box-shadow:0 4px 16px rgba(0,0,0,.4)", { textContent: "✎", title: "AI Inline Review" });
              fab.onclick = function () { if (open) { return; } open = true; capture().then(buildOverlay); };
              document.body.appendChild(fab);
            })();
        """.trimIndent()
    }
}
