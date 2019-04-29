CDX_PORT=8081
CDX_URL=http://localhost:$CDX_PORT/testcol
JAR=1

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

function check_negative {
    if curl -s "$1" | grep -q "$2"; then
        echo "FAIL Found '$2' on '$1' but wasn't expecting it"
        exit 1
    else
        echo PASS
    fi
}

function launch_cdx {
    mkdir -p target/data
    if [ $JAR == 1 ]; then
        java -jar ../target/outbackcdx-*.jar -p $CDX_PORT -d target/data &
    else
        java -cp `find ../target/outbackcdx-*.jar | head -n 1`:service/services.jar outbackcdx.Main -p $CDX_PORT -d target/data &
    fi
    children+=($!)
    wait_until_listening http://localhost:$CDX_PORT

    #
    # Load test data
    #
    curl -X POST --data-binary @test1.cdx $CDX_URL
    curl -X POST --data-binary @test2.cdx $CDX_URL
}

