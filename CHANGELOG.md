# OutbackCDX Changelog

## 1.3.0 (unreleased)

### New features

* `/{collection}/stats` now reports `latestSequenceNumber`, `nextReplicationSequence` and `oldestAvailableSequenceNumber` so replication lag and WAL headroom can be monitored. [#153](https://github.com/nla/outbackcdx/pull/153)
* A secondary can now be seeded or repaired by copying the collection directory (ideally a checkpoint) from the primary. On startup, a collection with data but no replication cursor resumes replicating from the copy's latest sequence. [#153](https://github.com/nla/outbackcdx/pull/153)

### Bug fixes

* Fixed a JVM crash (SIGSEGV) when requesting `/changes` for a sequence not covered by the retained WAL, such as on a collection that has never been written. [#153](https://github.com/nla/outbackcdx/pull/153)
* Fixed the replication cursor never advancing past the last batch, which caused an idle primary to re-send the same batch on every poll and could eventually leave the secondary behind the primary's WAL retention window. [#153](https://github.com/nla/outbackcdx/pull/153)
* `/changes` now returns `410 Gone` when the requested sequence has been purged from the WAL, instead of silently skipping records, and `204 No Content` when the secondary is caught up. [#153](https://github.com/nla/outbackcdx/pull/153)

### Changes

* The Docker image no longer includes the RocksDB `ldb` and `sst_dump` tools. Compiling them made image builds slow and prone to failure.

### Dependency upgrades

* **commons-codec**: 1.22.0 → 1.22.1
* **jackson-dataformat-cbor**: 2.22.0 → 2.22.3
* **jwarc**: 0.36.0 → 0.37.0
* **moment**: 2.30.1 → 2.31.0
* **nimbus-jose-jwt**: 10.9 → 10.10
* **snakeyaml-engine**: 3.0.1 → 3.1.1
* **undertow-core**: 2.3.24.Final → 2.3.26.Final

## 1.2.2 (2026-07-09)

### Bug fixes

* Fixed RocksDB resource leaks in replication. [#149](https://github.com/nla/outbackcdx/pull/149) [#150](https://github.com/nla/outbackcdx/pull/150)
* Return `401 Unauthorized` response on an invalid or expired access token.

### Dependency upgrades

* **rocksdbjni**: 10.10.1 → 10.10.1.1
* **jackson**: 2.21.3 → 2.22.0

## 1.2.1 (2026-06-08)

### Bug fixes

- Fixed query string parsing when using the default web server (no `-u` option). [#145](https://github.com/nla/outbackcdx/pull/145)

## 1.2.0 (2026-06-05)

### New features

- Added `/checkpoint` endpoint for live backups

### Dependency upgrades

* **commons-codec**: 1.20.0 → 1.22.0
* **jackson-dataformat-cbor**: 2.21.0 → 2.21.3
* **jwarc**: 0.34.0 → 0.36.0
* **maven-release-plugin**: 3.2.0 → 3.3.1
* **maven-shade-plugin**: 3.6.1 → 3.6.2
* **nimbus-jose-jwt**: 10.7 → 10.9
* **Package**: From → To
* **rocksdbjni**: 9.5.2 → 10.10.1
* **undertow-core**: 2.3.22.Final → 2.3.24.Final

## 1.1.1 (2026-01-19)

### Bug fixes

- Fixed `--context-path` when using the default web server (no `-u` option).

### Dependency upgrades

* **jackson-dataformat-cbor**: 2.20.1 → 2.21.0
* **jwarc**: 0.32.0 → 0.34.0
* **nimbus-jose-jwt**: 10.6 → 10.7
* **undertow-core**: 2.3.20.Final → 2.3.22.Final

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
