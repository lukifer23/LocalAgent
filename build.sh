#!/bin/bash
# LocalAgent Build Helper for Termux
export PATH=$HOME/bin:$PATH
export TMPDIR=$HOME/tmp
mkdir -p $TMPDIR

./gradlew assembleDebug "$@"
