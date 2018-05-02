OutbackCDX (nee tinycdxserver)
==============================

A [RocksDB]-based web archive index server which can serve records using Wayback's
RemoteResourceIndex (xmlquery) protocol.

* Realtime, incremental updates
* Compressed indexes (varint packing + snappy), typically 1/4 - 1/5 the size of CDX files.

Status and Limitations
----------------------

A couple of institutions are using this in production. We (National Library of Australia) use it for a ~1 TB (compressed), 8 billion record index.

* The dashabord / admin GUI is not fully functional yet
* Documentation could be improved a lot
* Requires Java 8
* No authentication (currently assumes you use a firewall)
* No sharding (could be added relatively easily but we don't currently need it, I would suggest maybe looking at Cassandra)
* No replication (you could use a HTTP load balancer though)
* Delete not yet implemented
* RemoteResourceIndex in OpenWayback is broken in 2.1 and 2.2 and requires a [patch]. This has been fixed in OpenWayback 2.3.

[RocksDB]: http://rocksdb.org/
[patch]: https://github.com/iipc/openwayback/pull/239

Usage
-----

Build:

    mvn package

Run:

    java -jar target/outbackcdx*.jar

Command line options:

    $ java -jar target/outbackcdx-0.3.2.jar -h
    Usage: java outbackcdx.Server [options...]
    

      -a url        Use a wayback access control oracle
      -b bindaddr   Bind to a particular IP address
      -d datadir    Directory to store index data under
      -i            Inherit the server socket via STDIN (for use with systemd, inetd etc)
      -p port       Local port to listen on
      -v            Verbose logging

The server supports multiple named indexes as subdirectories.  You can
load records into the index by POSTing them in the (11-field) CDX format Wayback uses:

    curl -X POST --data-binary @records.cdx http://localhost:8080/myindex

The canonicalized URL (first field) is ignored, OutbackCDX performs its own
canonicalization.

Using with OpenWayback
-----------------------

Point Wayback at a OutbackCDX index by configuring a RemoteResourceIndex. See the example RemoteCollection.xml shipped with OpenWayback.

```xml
    <property name="resourceIndex">
      <bean class="org.archive.wayback.resourceindex.RemoteResourceIndex">
        <property name="searchUrlBase" value="http://localhost:8080/myindex" />
      </bean>
    </property>
```

Using with pywb
---------------

Create a pywb config.yaml file containing:

```yaml
collections:
  testcol:
    archive_paths: /tmp/warcs/
    #archive_paths: http://remote.example.org/warcs/
    index:
      type: cdx
      api_url: http://localhost:8080/myindex?url={url}&closest={closest}&sort=closest
    
      # outbackcdx doesn't serve warc records 
      # so we blank replay_url to force pywb to read the warc file itself
      replay_url: ""
```

Access Control
--------------

Experimental support for access control is under early development, experimental support for it can be
can be enabled by setting the following environment variable:

    EXPERIMENTAL_ACCESS_CONTROL=1

Rules can be configured through the GUI. Have Wayback or other clients query a particular named access
point. For example to query the 'public' access point.

    http://localhost:8080/myindex/ap/public

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
