# Changelog

All notable changes to cordova-plugin-remote-injection are documented in this file.

## [0.6.1] - 2026-02-19

### Security
- **Android:** `fetchAndInject` is now gated behind a `CRIAllowFetchAndInject` preference (default: `false`). The action is disabled unless explicitly enabled in `config.xml`, preventing misuse if the plugin is included in production builds. Set `<preference name="CRIAllowFetchAndInject" value="true"/>` to enable.
- **Android:** `fetchAndInject` now validates URL schemes and rejects non-HTTP(S) URLs (e.g. `file://`, `content://`).

### Fixed
- **Android:** Replaced deprecated `AsyncTask.execute()` with `cordova.getThreadPool().execute()` for background work in `fetchAndInject`.
- **Android:** Fixed potential resource leak in `fetchAndInject` — response reader now uses try-with-resources.
- **Android:** Added `instanceof WebView` guard before casting engine view in both `injectCordova()` and `fetchAndInject`. Fails gracefully with an error message if a non-SystemWebView engine is used.
- **Android:** Added API 19+ runtime check before calling `evaluateJavascript()`. Logs an error on older devices instead of crashing.

## [0.6.0] - 2026-02-19

### Changed
- **Android:** Replaced `data:` URI script injection with `WebView.evaluateJavascript()` in `injectCordova()`. This fixes compatibility with remote pages that use nonce-based Content Security Policy (CSP), which block `data:text/javascript` URIs in `script-src` directives. ([APPS-6014](https://doxodev.atlassian.net/browse/APPS-6014))

### Added
- **Android:** New `fetchAndInject` exec action. Fetches JavaScript from HTTP/HTTPS URLs via native `HttpURLConnection` (outside the WebView's CSP context) and injects the response via `evaluateJavascript()`. This enables dev-mode add-on loading from a Vite dev server without being blocked by the remote page's CSP. Called via `cordova.exec(success, error, 'RemoteInjection', 'fetchAndInject', [url1, url2, ...])`.

### Notes
- **Android only.** iOS injection (`evaluateJavaScript:`) was already CSP-compatible and is unchanged.
- No changes to the plugin's public preferences (`CRIInjectFirstFiles`, `CRIPageLoadPromptInterval`).
- Requires Android API 19+ (for `WebView.evaluateJavascript()`).

## [0.5.2]

- Remove all UIWebView code (Apple requirement).
- Added deprecation notice to README.

## [0.5.1]

- Fix unexpected token error caused by the default base64 encoding (#25).

## [0.4.1]

- Fix unicode character in injected javascript issue (#20).

## [0.4.0]

- Added support for WKWebView on iOS.

## [0.3.1]

- Fix for issue #14.

## [0.3.0]

- User prompt on slow requests (CRIPageLoadPromptInterval preference).
- Added CSP requirements to the FAQ.

## [0.2.0]

- Support for cordova-ios 4.x (#1).

## [0.1.0]

- Initial release.
