# OutbackCDX Changelog

## 1.1.0 (2025-12-19)

### New features

- A bare `*` can now be used as an access control rule pattern to set a rule that affects all URLs.
- Added `--warc-base-url` option and basic replay feature. This is experimental.
- Added `--service-worker` option to enable direct use of replay service worker (e.g. wabac.js, reconstructive.js).

### Bug fixes

- Enabled TCP nodelay for faster responses.
- Fallback to urlcanon URL parser for URLs that Java's URL parser cannot parse.

### Removals

- Replaced NanoHTTPD with the builtin Java HttpServer.
- Removed support for Java 8. Java 11 or later is now required.

### Dependency upgrades

* **commons-codec**: 1.15 → 1.17.1
* **httpclient**: 4.5.13 → 4.5.14
* **jackson-dataformat-cbor**: 2.15.1 → 2.17.2
* **jwarc**: 0.31.1
* **lodash (webjars)**: 4.17.4 → 4.17.21
* **moment (webjars)**: 2.24.0 → 2.30.1
* **nimbus-jose-jwt**: 9.31 → 10.0.2
* **rocksdbjni**: 8.1.1.1 → 9.5.2
* **snakeyaml-engine**: 2.0 → 2.7
* **undertow-core**: 2.2.24.Final → 2.3.17.Final
