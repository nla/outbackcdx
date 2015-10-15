tinycdxserver
=============

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
* Pagination and delete are not yet implemented
* RemoteResourceIndex in OpenWayback is broken (in 2.1) and requires a [patch]

[RocksDB]: http://rocksdb.org/
[patch]: https://github.com/iipc/openwayback/pull/239

Usage
-----

If you wish to use Snappy compression you will need to build RocksDB from source.
The Maven central releases silently ignore the Snappy option.

Edit pom.xml and change the rocksdb dependency to point at your build or
uncomment the official release.

    mvn package
    java -jar target/tinycdxserver.jar -d /data -p 8080

The server supports multiple named indexes as subdirectories.  You can
load records into the index by POSTing them in the (11-field) CDX format Wayback uses:

    curl -X POST --data-binary @records.cdx http://localhost:8080/myindex

The canonicalized URL (first field) is ignored, tinycdxserver performs its own
canonicalization.

Exclusions
----------

Wayback's RemoteResourceIndex currently bypasses some of its access control
configuration.  For this reason tinycdxserver currently supports
filtering query results using an [exclusions oracle].  Set the URL of
exclusions oracle using the `-a` command-line option.

Source IP address based filtering is not currently supported. It may be
more preferable to fix RemoteResourceIndex.

[exclusions oracle]: https://github.com/iipc/openwayback-access-control

Future Work
-----------

Other than fixing the above limitations, index size could be further reduced by:

* Representing WARC files using a numeric ID
* Truncating or omitting the SHA1 digests (Wayback only uses them to asterisk changed records)

It may be handy to have a secondary index for looking up records by digest.
