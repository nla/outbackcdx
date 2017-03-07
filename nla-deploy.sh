#!/bin/bash
mvn package
cp target/outbackcdx*.jar $1/outbackcdx.jar
