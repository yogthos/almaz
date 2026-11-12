#!/bin/bash
# Build libalmazimg — the almaz pixel engine (libjpeg, libpng, libtiff).
# GPL-2.0-or-later.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p lib
if [ "$(uname)" = "Darwin" ]; then
  P="${HOMEBREW_PREFIX:-/opt/homebrew}"
  # almazimg_metal.m: renders on the GPU (falls back to the CPU without one)
  clang -c -std=c11 -O3 -Wall -Wno-unused-function -I"$P/include" -o lib/almazimg.o almazimg.c
  clang -c -O3 -Wall -fobjc-arc -o lib/almazimg_metal.o almazimg_metal.m
  clang -dynamiclib -O3 -I"$P/include" -o lib/libalmazimg.dylib lib/almazimg.o lib/almazimg_metal.o inpaint.c exif.c sha256.c \
    -L"$P/lib" -ljpeg -lpng16 -ltiff -lm -lpthread -framework Metal -framework MetalPerformanceShaders -framework Foundation
  rm -f lib/almazimg.o lib/almazimg_metal.o
  echo "built lib/libalmazimg.dylib"
else
  cc -shared -fPIC -std=gnu11 -O3 -Wall -Wno-unused-function \
    $(pkg-config --cflags libjpeg libpng libtiff-4 2>/dev/null) -o lib/libalmazimg.so almazimg.c inpaint.c exif.c sha256.c \
    $(pkg-config --libs libjpeg libpng libtiff-4 2>/dev/null || echo -ljpeg -lpng -ltiff) -lm -lpthread
  echo "built lib/libalmazimg.so"
fi
