#!/usr/bin/env bash
# Builds the library and runs the tests with nothing but a JDK (21+), for
# environments without Maven. Usage:
#
#   ./build.sh                 # compile + jar + unit tests + integration tests against 127.0.0.1:22119
#   LOCKING_CENTER_ADDRESS=host:port ./build.sh
#   LOCKING_CENTER_SKIP_INTEGRATION=1 ./build.sh   # unit tests only
#   ./build.sh --no-test       # compile + jar only
set -euo pipefail

cd "$(dirname "$0")"

rm -rf target
mkdir -p target/classes target/test-classes

echo "compiling library"
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -d target/classes $(find src/main/java -name '*.java')

echo "packaging target/locking-center-client.jar"
jar --create --file target/locking-center-client.jar -C target/classes .

if [[ "${1:-}" == "--no-test" ]]; then
    exit 0
fi

echo "compiling tests"
javac --release 21 -encoding UTF-8 -Xlint:all -Werror -cp target/classes -d target/test-classes $(find src/test/java -name '*.java')

echo "running tests"
java -cp target/classes:target/test-classes com.freakmaxi.lockingcenter.TestRunner
