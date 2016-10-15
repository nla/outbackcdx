OutbackCDX (nee tinycdxserver)
==============================

A [RocksDB]-based web archive index server which can serve records using Wayback's
RemoteResourceIndex (xmlquery) protocol.

* Realtime, incremental updates
* Compressed indexes (varint packing + snappy), typically 1/4 - 1/5 the size of CDX files.

Status and Limitations
----------------------

We're (National Library of Australia) using this in production but under a
relatively light traffic load.

* Requires Java 8
* No authentication (currently assumes you use a firewall)
* No sharding (could be added relatively easily but we don't currently need it)
* No replication (you could use a HTTP load balancer though)
* Delete not yet implemented
* RemoteResourceIndex in OpenWayback is broken in 2.1 and 2.2 and requires a [patch]. The patch will be included in OpenWayback 2.3.

[RocksDB]: http://rocksdb.org/
[patch]: https://github.com/iipc/openwayback/pull/239

Usage
-----

The server supports multiple named indexes as subdirectories.  You can
load records into the index by POSTing them in the (11-field) CDX format Wayback uses:

    curl -X POST --data-binary @records.cdx http://localhost:8080/myindex

The canonicalized URL (first field) is ignored, OutbackCDX performs its own
canonicalization.


Exclusions
----------

Wayback's RemoteResourceIndex currently bypasses some of its access control
configuration.  For this reason OutbackCDX currently supports
filtering query results using an [exclusions oracle].  Set the URL of
exclusions oracle using the `-a` command-line option.

Source IP address based filtering is not currently supported. It may be
more preferable to fix RemoteResourceIndex.

[exclusions oracle]: https://github.com/iipc/openwayback-access-control

Canonicalisation Aliases
------------------------

Alias records allow the grouping of URLs so they will deliver as if they are different snapshots of the same page.

    @alias <source-url> <target-url>
    
For example:

    @alias http://legacy.example.org/page-one http://www.example.org/page1
    @alias http://legacy.example.org/page-two http://www.example.org/page2

Aliases do not currently work with url prefix queries. Aliases are resolved after normal canonicalisation rules
are applied.

Aliases can be mixed with regular CDX lines either in the same file or separate files and in any order. Any existing records that the alias rule affects the canonicalised URL for will be updated when the alias is added to the index.

Future Work
-----------

Other than fixing the above limitations, index size could be further reduced by:

* Representing WARC files using a numeric ID
* Truncating or omitting the SHA1 digests (Wayback only uses them to asterisk changed records)

It may be handy to have a secondary index for looking up records by digest.
