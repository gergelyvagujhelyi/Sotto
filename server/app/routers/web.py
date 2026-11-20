"""For browsers and Android rather than the app: the invite page, and Android App Links.

An invite link is this relay's own address with the code after a ``#``:
``https://<relay>/invite#c=<code>&s=<inviter's share code>``. Browsers never send the part
after ``#``, so the code never reaches this server or its logs; the page reads it in the
browser. With Sotto installed, Android opens such links in the app instead (App Links,
verified through ``/.well-known/assetlinks.json``), so the page is for everyone else: it
offers the download, then opens the invite in Sotto through the app's own link form,
``sotto://invite?r=<relay>&c=<code>&s=<share code>``.
"""

import base64
import hashlib
import html

from fastapi import APIRouter
from fastapi.responses import HTMLResponse, JSONResponse

from app import config

router = APIRouter(tags=["web"])

_STYLE = """
:root { color-scheme: dark; }
* { box-sizing: border-box; }
[hidden] { display: none !important; }
body {
  margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
  padding: 24px 16px; color: #ECEAF6; font: 16px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  background: radial-gradient(circle at 20% 10%, #3B2F7A 0%, transparent 55%),
              radial-gradient(circle at 85% 90%, #0E4A5A 0%, transparent 50%), #0B0B14;
}
main {
  width: 100%; max-width: 26rem; padding: 28px 24px; border-radius: 28px;
  background: rgba(255, 255, 255, 0.06); border: 1px solid rgba(255, 255, 255, 0.12);
}
.orb {
  width: 64px; height: 64px; border-radius: 50%; margin: 0 auto 16px;
  background: radial-gradient(circle at 35% 30%, #FFC48A, #F06BA8 55%, #7C5CFF);
  box-shadow: 0 0 40px rgba(240, 107, 168, 0.45);
}
h1 { font-size: 1.6rem; line-height: 1.2; margin: 0 0 8px; text-align: center; }
p { margin: 0 0 16px; color: #B9B6CC; }
.lead { text-align: center; }
ol { list-style: none; margin: 24px 0 0; padding: 0; }
li { margin: 0 0 20px; }
.step { display: block; font-size: 0.8rem; letter-spacing: 0.08em; text-transform: uppercase; color: #8F8BA8; margin-bottom: 8px; }
a.button {
  display: block; text-align: center; text-decoration: none; font-weight: 600;
  padding: 14px 16px; border-radius: 999px; color: #0B0B14; background: #ECEAF6;
}
a.button.primary { color: #FFFFFF; background: linear-gradient(90deg, #7C5CFF, #F06BA8); }
button.link {
  display: block; width: 100%; margin-top: 12px; padding: 10px; border: 0; background: none;
  color: #B9B6CC; font: inherit; font-size: 0.9rem; text-decoration: underline; cursor: pointer;
}
.small { font-size: 0.85rem; margin: 8px 0 0; }
"""

# No template values inside: the Content-Security-Policy allows exactly this script by its hash.
_SCRIPT = """
(function () {
  var params = new URLSearchParams(location.hash.slice(1));
  var code = (params.get('c') || '').toUpperCase().replace(/[^0-9A-Z]/g, '')
    .replace(/O/g, '0').replace(/[IL]/g, '1');
  var share = params.get('s') || '';
  document.getElementById('host').textContent = location.host;
  if (!/^[0-9A-HJKMNP-TV-Z]{12}$/.test(code)) {
    document.getElementById('steps').hidden = true;
    document.getElementById('broken').hidden = false;
    return;
  }
  var link = 'sotto://invite?r=' + encodeURIComponent(location.origin) + '&c=' + code;
  if (/^[A-Za-z0-9_-]{16}$/.test(share)) link += '&s=' + share;
  document.getElementById('open').href = link;
  var copy = document.getElementById('copy');
  copy.addEventListener('click', function () {
    navigator.clipboard.writeText(location.href).then(function () {
      copy.textContent = 'Copied. Paste it in Sotto\u2019s setup.';
    });
  });
})();
"""

_ICON = (
    "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E"
    "%3Ccircle cx='16' cy='16' r='15' fill='%23F06BA8'/%3E%3C/svg%3E"
)


def _csp_source(text: str) -> str:
    digest = hashlib.sha256(text.encode("utf-8")).digest()
    return "'sha256-" + base64.b64encode(digest).decode("ascii") + "'"


INVITE_PAGE_CSP = (
    "default-src 'none'; "
    f"script-src {_csp_source(_SCRIPT)}; "
    f"style-src {_csp_source(_STYLE)}; "
    "img-src data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
)


def _invite_page() -> str:
    download = html.escape(config.APP_DOWNLOAD_URL, quote=True)
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<meta name="referrer" content="no-referrer">
<title>Join me on Sotto</title>
<link rel="icon" href="{_ICON}">
<style>{_STYLE}</style>
</head>
<body>
<main>
  <div class="orb"></div>
  <h1>Join me on Sotto</h1>
  <p class="lead">Sotto is an end-to-end encrypted messenger for Android, with calls. This
  invite lets you join the relay <strong id="host"></strong>, and adds the person who sent it as
  your first contact.</p>
  <ol id="steps">
    <li>
      <span class="step">Step 1</span>
      <a class="button" href="{download}">Download Sotto</a>
      <p class="small">Android asks once to allow installing apps from your browser.
      Already have Sotto? Go on to step 2.</p>
    </li>
    <li>
      <span class="step">Step 2</span>
      <a class="button primary" id="open" href="#">Open the invite in Sotto</a>
      <p class="small">The invite works once. If the button doesn't open Sotto, copy this page's
      link and paste it in Sotto's setup: it holds everything.</p>
      <button class="link" id="copy" type="button">Copy the invite link</button>
    </li>
  </ol>
  <p id="broken" hidden>This invite link is incomplete. Ask the person who sent it for the
  whole link, or for a new invite.</p>
  <noscript><p>Open this link on your Android phone, in a browser with JavaScript on, or with
  Sotto installed.</p></noscript>
</main>
<script>{_SCRIPT}</script>
</body>
</html>
"""


@router.get("/invite", response_class=HTMLResponse, include_in_schema=False)
async def invite_page() -> HTMLResponse:
    """The page an invite link opens in a browser. The same for every invite."""
    return HTMLResponse(
        _invite_page(),
        headers={
            "Content-Security-Policy": INVITE_PAGE_CSP,
            "Cache-Control": "no-cache",
            "X-Robots-Tag": "noindex",
        },
    )


@router.get("/.well-known/assetlinks.json", include_in_schema=False)
async def asset_links() -> JSONResponse:
    """Which app may open this relay's invite links directly (Android App Links)."""
    if not config.APP_LINK_PACKAGE or not config.APP_LINK_CERT_FINGERPRINTS:
        return JSONResponse([])
    return JSONResponse([{
        "relation": ["delegate_permission/common.handle_all_urls"],
        "target": {
            "namespace": "android_app",
            "package_name": config.APP_LINK_PACKAGE,
            "sha256_cert_fingerprints": config.APP_LINK_CERT_FINGERPRINTS,
        },
    }])
