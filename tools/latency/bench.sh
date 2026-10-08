#!/usr/bin/env bash
# usage: bench.sh <serial> <name> <ocrThreads> [tests...]
serial=$1; name=$2; threads=$3; shift 3
out=$(dirname "$0")/results
mkdir -p $out
for t in "$@"; do
  adb -s $serial logcat -c
  adb -s $serial shell run-as com.xaxaxax.moonclicker.engine.test rm -f files/lat.log
  adb -s $serial logcat -s LAT > $out/$name-$t.log &
  logpid=$!
  temp=$(adb -s $serial shell dumpsys battery | grep temperature | tr -dc 0-9)
  start=$(date +%s)
  adb -s $serial shell am instrument -w -e class com.xaxaxax.moonclicker.script.LatencyBenchTest#$t \
    -e rounds ${ROUNDS:-5} -e ocrThreads $threads \
    com.xaxaxax.moonclicker.engine.test/androidx.test.runner.AndroidJUnitRunner > $out/$name-$t.instrument.txt 2>&1
  sleep 3; kill $logpid
  adb -s $serial shell run-as com.xaxaxax.moonclicker.engine.test cat files/lat.log > $out/$name-$t.file.log
  echo "$name $t done in $(( $(date +%s) - start ))s temp=$temp $(tail -1 $out/$name-$t.instrument.txt)"
  sleep 60
done
echo "$name ALL DONE"
