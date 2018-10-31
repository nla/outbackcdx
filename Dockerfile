FROM maven:3-jdk-8 as build-env

RUN apt-get update && apt-get install -y libsnappy-dev \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /build

COPY pom.xml /build/pom.xml
RUN mvn -B -f /build/pom.xml -s /usr/share/maven/ref/settings-docker.xml dependency:resolve-plugins dependency:go-offline

COPY src /build/src
COPY docs /build/docs
COPY resources /build/resources

RUN export JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF8 && \
    mvn -B -s /usr/share/maven/ref/settings-docker.xml -DskipTests package && \
    mvn package

FROM java:8

RUN apt-get update && apt-get install -y libsnappy-dev \
 && rm -rf /var/lib/apt/lists/*

COPY --from=build-env /build/target/outbackcdx-*.jar outbackcdx.jar

RUN mkdir /cdx-data

EXPOSE 8080

CMD java -jar outbackcdx.jar -d /cdx-data -p 8080 -b 0.0.0.0
