CDX_PORT=8081
CDX_URL=http://localhost:$CDX_PORT/testcol

children=()

function cleanup {
   echo kill "${children[@]}"
   kill "${children[@]}"
}

trap cleanup EXIT

function wait_until_listening {
    n=0
    while ! curl -s "$1" > /dev/null; do
        sleep 0.1
        n=$(( $n + 1 ))
        if (( $n > 50 )); then
           echo "timed out waiting for: $1"
           exit 1
        fi
    done
}

function check {
    if curl -s "$1" | grep -q "$2"; then
        echo PASS
    else
        echo "FAIL Couldn't find '$2' on '$1'"
        exit 1
    fi
}

function launch_cdx {
    mkdir -p target/data
    java -jar ../target/tinycdxserver-*.jar -p $CDX_PORT -d target/data &
    children+=($!)
    wait_until_listening http://localhost:$CDX_PORT

    #
    # Load test data
    #
    curl -X POST --data-binary @test1.cdx $CDX_URL
    curl -X POST --data-binary @test2.cdx $CDX_URL
}

