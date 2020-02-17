#!/bin/bash

set -e -u

source common.sh

function fetch {
   [ -f deps/$1 ] || curl -o deps/$1 $2
}

#
# Download dependencies
#

mkdir -p deps
fetch wayback.war https://repo1.maven.org/maven2/org/netpreserve/openwayback/openwayback-webapp/2.3.1/openwayback-webapp-2.3.1.war
fetch jetty-runner.jar https://repo1.maven.org/maven2/org/eclipse/jetty/jetty-runner/9.4.0.M1/jetty-runner-9.4.0.M1.jar

#
# Prepare wayback
#

rm -rf target
mkdir -p target/wayback
(cd target/wayback && jar -xf ../../deps/wayback.war)
cp wayback.xml target/wayback/WEB-INF

#
# Launch cdx server
#

launch_cdx

#
# Launch wayback
#

export WAYBACK_WARC_URL="$PWD/"
export WAYBACK_CDX_URL=http://localhost:$CDX_PORT/testcol
WAYBACK_URL=http://localhost:8080/wayback
java -jar deps/jetty-runner.jar --port 8080 --path / target/wayback/ &>target/wayback.log &
children+=($!)
wait_until_listening $WAYBACK_URL

#
# Do tests
#

PAGE=http://data.webarchive.org.uk/crawl-test-site/

check $WAYBACK_URL/*/$PAGE 20161016214133
check $WAYBACK_URL/*/$PAGE 20161016214156
check $WAYBACK_URL/20161016214133/$PAGE 'Simple Text Documents'

