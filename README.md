OutbackCDX (nee tinycdxserver)
==============================

A [RocksDB](https://rocksdb.org/)-based capture index (CDX) server for web archives.

* [API Documentation](https://nla.github.io/outbackcdx/api.html)

Features:

* Speaks both [OpenWayback](https://github.com/iipc/openwayback/) (XML) and [PyWb](https://github.com/webrecorder/pywb) (JSON) CDX protocols
* Realtime, incremental updates
* Compressed indexes (varint packing + snappy), typically 1/4 - 1/5 the size of CDX files.
* Access control (experimental, see below)

Things it doesn't do (yet):

* Authentication (use a firewall or reverse proxy for now)
* Deletes
* Sharding, replication
* CDXJ

Used in production at the National Library of Australia and British Library with
8-9 billion record indexes.

Usage
-----

Build:

    mvn package

Run:

    java -jar target/outbackcdx*.jar

Command line options:

    $ java -jar target/outbackcdx-0.3.2.jar -h
    Usage: java outbackcdx.Server [options...]
    

      -a url                Use a wayback access control oracle
      -b bindaddr           Bind to a particular IP address
      -d datadir            Directory to store index data under
      -i                    Inherit the server socket via STDIN (for use with systemd, inetd etc)
      -j jwks-url perm-path Use JSON Web Tokens for authorization
      -k url realm clientid Use a Keycloak server for authorization
      -p port               Local port to listen on
      -t count              Number of web server threads
      -v                    Verbose logging

The server supports multiple named indexes as subdirectories.  Currently indexes
are created automatically when you first write records to them.

### Loading Records

OutbackCDX does not include a CDX indexing tool for reading WARC or ARC files. Use
the `cdx-indexer` scripts included with OpenWayback or PyWb.

You can load records into the index by POSTing them in the (11-field) CDX format
Wayback uses:

    $ cdx-indexer mycrawlw.warc.gz > records.cdx
    $ curl -X POST --data-binary @records.cdx http://localhost:8080/myindex
    Added 542 records

The canonicalized URL (first field) is ignored, OutbackCDX performs its own
canonicalization.

**Limitation:** Loading an extremely large number of CDX records in one POST request
can cause an [out of memory error](https://github.com/nla/outbackcdx/issues/13). 
Until this is fixed you may need to break your request up into several smaller ones. 
Most users send one POST per WARC file.

### Querying

Records can be queried in CDX format:

    $ curl 'http://localhost:8080/myindex?url=example.org'
    org,example)/ 20030402160014 http://example.org/ text/html 200 MOH7IEN2JAEJOHYXIEPEEGHOHG5VI=== - - 2248 396 mycrawl.warc.gz

CDX formatted as JSON arrays:
    
    $ curl 'http://localhost:8080/myindex?url=example.org&output=json'
    [
      [
        "org,example)/",
        20030402160014,
        "http://example.org/",
        "text/html",
        200,
        "MOH7IEN2JAEJOHYXIEPEEGHOHG5VI===",
        2248,
        396,
        "mycrawl.warc.gz"
      ]
    ]

OpenWayback "OpenSearch" XML:

    $ curl 'http://localhost:8080/myindex?q=type:urlquery+url:http%3A%2F%2Fexample.org%2F'
    <?xml version="1.0" encoding="UTF-8"?>
    <wayback>
       <request>
           <startdate>19960101000000</startdate>
           <enddate>20180526162512</enddate>
           <type>urlquery</type>
           <firstreturned>0</firstreturned>
           <url>org,example)/</url>
           <resultsrequested>10000</resultsrequested>
           <resultstype>resultstypecapture</resultstype>
       </request>
       <results>
           <result>
               <compressedoffset>396</compressedoffset>
               <compressedendoffset>2248</compressedendoffset>
               <mimetype>text/html</mimetype>
               <file>mycrawl.warc.gz</file>
               <redirecturl>-</redirecturl>
               <urlkey>org,example)/</urlkey>
               <digest>MOH7IEN2JAEJOHYXIEPEEGHOHG5VI===</digest>
               <httpresponsecode>200</httpresponsecode>
               <robotflags>-</robotflags>
               <url>http://example.org/</url>
               <capturedate>20030402160014</capturedate>
           </result>
       </results>
    </wayback>

Query URLs that match a given SURT prefix:

    $ curl 'http://localhost:8080/myindex?url=(org,example&matchType=prefix'
    
Find the first 5 URLs with a given domain:

    $ curl 'http://localhost:8080/myindex?url=example.org&matchType=domain&limit=5'

Find the next 10 URLs in the index starting from the given SURT:

    $ curl 'http://localhost:8080/myindex?url=(org,example,&matchType=range&limit=10'

Return results in reverse order:
 
    $ curl 'http://localhost:8080/myindex?url=example.org&sort=reverse'
 
Return results ordered closest to furthest from a given timestamp:

    $ curl 'http://localhost:8080/myindex?url=example.org&sort=closest&closest=20030402172120'

See the [API Documentation](https://nla.github.io/outbackcdx/api.html) for more details
about the available options.
        
Configuring replay tools
------------------------

### OpenWayback

Point Wayback at a OutbackCDX index by configuring a RemoteResourceIndex. See the example RemoteCollection.xml shipped with OpenWayback.

```xml
    <property name="resourceIndex">
      <bean class="org.archive.wayback.resourceindex.RemoteResourceIndex">
        <property name="searchUrlBase" value="http://localhost:8080/myindex" />
      </bean>
    </property>
```

### PyWb

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

### Heritrix

The ukwa-heritrix project includes [some classes](https://github.com/ukwa/ukwa-heritrix/blob/21d31329065f2a6a68186309757a5644af00daec/src/main/java/uk/bl/wap/modules/uriuniqfilters/OutbackCDXRecentlySeenUriUniqFilter.java)
that allow OutbackCDX to be used as a source of deduplication data for Heritrix crawls.

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

Authorization
-------------

By default OutbackCDX is unsecured and assumes some external method of authorization such as firewall
rules or a reverse proxy are used to secure it. Take care not to expose it to the public internet.

Alternatively one of the following authorization methods can be enabled.

### Generic JWT authorization

Authorization to modify the index and access control rules can be controlled using [JSON Web Tokens](https://jwt.io/).
To enable this you will typically use some sort of separate authentication server to sign the JWTs.

OutbackCDX's `-j` option takes two arguments, a JWKS URL for the public key of the auth server and a slash-delimited
path for where to find the list of permissions in the JWT received as a HTTP bearer token. Refer to your auth server's
documentation for what to use.

Currently the OutbackCDX web dashboard does not support generic JWT/OIDC authorization. (Patches welcome.)

### Keycloak authorization

OutbackCDX can use (Keycloak)[https://www.keycloak.org/] as an auth server to secure both the API and dashboard.

1. In your Keycloak realm's settings create a new client for OutbackCDX with the protocol `openid-connect` and
   the URL of your OutbackCDX instance.
3. Under the client's roles tab create the following roles:
    * index_edit
    * rules_edit
    * policies_edit
4. Map your users or service accounts to these client roles as appropriate.
5. Run OutbackCDX with this option:

```
-k https://{keycloak-server}/auth {realm} {client-id}
```

Note: JWT authentication will be enabled automatically when using Keycloak. You don't need to set the `-j` option.