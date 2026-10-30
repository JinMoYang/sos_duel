#!/bin/bash
# Runs one of the app's JVM tools (an object with a main() under app/src/test) on the PC, with the
# app's own compiled code, so the phone and the PC compute features the same way.
#
#   tools/curve/run_jvm.sh ToleranceCurveSosCf <sos_cf_v1 dir> <out curve.txt> [-Dsos.speed=gyro]
#   tools/curve/run_jvm.sh NaeTrainSosCf <sos_cf_v1 dir> <out dir>
#
# ToleranceCurveSosCf builds the proposed method's tolerance curve (the app ships
# app/src/main/assets/tolerance_curve_gyro.txt, built with -Dsos.speed=gyro); NaeTrainSosCf
# trains the Neural-AE baseline's weights (push nae_hist_scalar_3x3.bin to
# /sdcard/Android/data/com.sos.integrate/files/). Both read the counterfactual record sos_cf_v1
# (collect_cf, 2026-10-16), shared apart from the repository.
#
# Needs JAVA_HOME (JDK 21) and the classes: ./gradlew :app:compileDebugUnitTestKotlin first.
# BUILD_DIR overrides where the build output lives (default app/build).
set -e
cd "$(dirname "$0")/../.."
tool=$1; shift
B=${BUILD_DIR:-app/build}/tmp/kotlin-classes
props=(); args=()
for a in "$@"; do case $a in -D*) props+=("$a");; *) args+=("$a");; esac; done
stdlib=$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" \
  -name 'kotlin-stdlib-2*.jar' ! -name '*sources*' | sort | tail -1)
sep=":"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) sep=";"; stdlib=$(cygpath -m "$stdlib"); B=$(cygpath -m "$B");; esac
"$JAVA_HOME/bin/java" -Xmx6g "${props[@]}" -cp "$B/debug$sep$B/debugUnitTest$sep$stdlib" \
  "com.example.activeperception.acquire.$tool" "${args[@]}"
