#!/bin/bash
# LocalAgent Build Helper for Termux
export TMPDIR=$HOME/tmp
mkdir -p $TMPDIR

./gradlew assembleDebug "$@"
