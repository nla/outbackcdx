#!/bin/bash

set -e -u

source common.sh

JAVA_ARGS=-Xbootclasspath/a:service/

rm -rf target

launch_cdx


