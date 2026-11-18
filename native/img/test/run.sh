#!/bin/bash
# Allocation-failure tests for the pixel engine (CPU paths; the GPU is stubbed).
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p ../lib
if [ "$(uname)" = "Darwin" ]; then
  P="${HOMEBREW_PREFIX:-/opt/homebrew}"
  INC="-I$P/include"; LIBS="-L$P/lib -ljpeg -lpng16 -ltiff"
else
  INC="$(pkg-config --cflags libjpeg libpng libtiff-4 2>/dev/null)"
  LIBS="$(pkg-config --libs libjpeg libpng libtiff-4 2>/dev/null || echo -ljpeg -lpng -ltiff)"
fi
cc -std=gnu11 -O1 -g -Wall -Wno-unused-function -DALMZ_NO_GPU $INC -o ../lib/alloc_test \
  alloc_test.c ../inpaint.c ../exif.c ../sha256.c $LIBS -lm -lpthread
../lib/alloc_test
