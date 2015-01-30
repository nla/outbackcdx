#!/bin/bash
mvn package
cp target/tiny*.jar $1
