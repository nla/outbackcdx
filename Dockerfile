FROM maven:3-adoptopenjdk-11 as build-env

RUN apt-get update \
 && apt-get install -y libsnappy-dev \
 && apt-get install -y build-essential \
 && rm -rf /var/lib/apt/lists/*

# add rocksdb tools
# see outbackcdx pom.xml for rocksdb version (rocksdbjni) and
# check branches with https://github.com/facebook/rocksdb
RUN cd /tmp && \
    git clone https://github.com/facebook/rocksdb.git && \
    cd rocksdb && \
    git checkout 6.0.fb && \
    DEBUG_LEVEL=0 CXXFLAGS='-Wno-error=deprecated-copy -Wno-error=pessimizing-move -Wno-error=class-memaccess' make tools && \
    cp /tmp/rocksdb/ldb /usr/bin/ && \
    cp /tmp/rocksdb/sst_dump /usr/bin/

WORKDIR /build

COPY pom.xml /build/pom.xml
RUN mvn -B -f /build/pom.xml -s /usr/share/maven/ref/settings-docker.xml dependency:resolve-plugins dependency:go-offline

COPY src /build/src
COPY docs /build/docs
COPY resources /build/resources

RUN export JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF8 && \
    mvn -B -s /usr/share/maven/ref/settings-docker.xml -DskipTests package && \
    mvn package

FROM adoptopenjdk:11-jdk-hotspot

RUN apt-get update && apt-get install -y libsnappy-dev dumb-init \
 && rm -rf /var/lib/apt/lists/*


COPY --from=build-env /build/target/outbackcdx-*.jar outbackcdx.jar
COPY --from=build-env /usr/bin/ldb /usr/bin
COPY --from=build-env /usr/bin/sst_dump /usr/bin

RUN mkdir /cdx-data

EXPOSE 8080

ENTRYPOINT ["/usr/bin/dumb-init", "--"]
CMD java -jar outbackcdx.jar -v -d /cdx-data -p 8080 -b 0.0.0.0

