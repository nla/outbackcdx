#!/bin/bash

set -e -u

cd service
javac -cp ~/.m2/repository:../../target/classes outbackcdx/services/service.java
jar cvf services.jar
cd ..

source common.sh

export FILTER_PLUGINS=1
JAR=0
launch_cdx

rm -rf target

curl -sSvg "$CDX_URL?url=metadata://&matchType=prefix" 
