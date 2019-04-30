#!/bin/bash

set -e -u

pushd service
javac -cp ~/.m2/repository:../../target/classes outbackcdx/services/service.java
jar cvf services.jar .
popd

source common.sh

export FILTER_PLUGINS=1
JAR=0
launch_cdx

rm -rf target

check_negative "$CDX_URL?url=metadata://&matchType=prefix" "metadata"
