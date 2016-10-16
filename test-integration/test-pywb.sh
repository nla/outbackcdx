#!/bin/bash
set -e -u

source common.sh

#
# Fetch pywb
#

mkdir -p deps
[ -d deps/venv ] || virtualenv deps/venv
PS1=dummy
source deps/venv/bin/activate
[ -f deps/venv/bin/wayback ] || pip install pywb

#
# Launch cdx server
#

launch_cdx

#
# Launch pywb
#

mkdir -p target
wayback &> target/pywb.log &
children+=($!)
wait_until_listening http://localhost:8080/testcol

#
# Perform tests
#

WAYBACK_URL=http://localhost:8080/testcol
PAGE=http://data.webarchive.org.uk/crawl-test-site/

check $WAYBACK_URL/*/$PAGE 20161016214133
check $WAYBACK_URL/*/$PAGE 20161016214156
#check $WAYBACK_URL/20161016214133/$PAGE 'Simple Text Documents'
