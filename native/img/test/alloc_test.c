/* Allocation-failure tests for almazimg.c: every allocation is made to fail in
 * turn (the Nth malloc/calloc returns NULL) and each API must either return
 * NULL / an error or degrade, never crash. Run: native/img/test/run.sh
 * GPL-2.0-or-later. */
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int fail_at = -1; /* fail the allocation with this index; -1 never */
static int n_allocs = 0;
static pthread_mutex_t alloc_lock = PTHREAD_MUTEX_INITIALIZER;

static int should_fail(void) {
  pthread_mutex_lock(&alloc_lock);
  const int f = (n_allocs++ == fail_at);
  pthread_mutex_unlock(&alloc_lock);
  return f;
}
static void *test_malloc(size_t n) { return should_fail() ? NULL : malloc(n); }
static void *test_calloc(size_t a, size_t b) { return should_fail() ? NULL : calloc(a, b); }

#define malloc test_malloc
#define calloc test_calloc
#include "../almazimg.c"
#undef malloc
#undef calloc

typedef int (*scenario)(void); /* 0 ok; the scenario must not crash */

/* how many allocations a scenario makes when none fail */
static int count_allocs(scenario s) {
  fail_at = -1;
  n_allocs = 0;
  s();
  return n_allocs;
}

static int img_new(void) {
  AlmzImg *img = almz_img_new(300, 200);
  if (img) {
    for (int k = 0; k < img->nlev; k++)
      if (!img->lev[k]) return 1; /* a half-built image must never escape */
    almz_img_free(img);
  }
  return 0;
}

static int img_copy(void) {
  int saved = fail_at;
  fail_at = -1;
  AlmzImg *src = almz_img_new(300, 200);
  const int base = n_allocs;
  fail_at = saved < 0 ? -1 : saved + base;
  AlmzImg *c = almz_img_copy(src);
  int bad = 0;
  if (c) {
    for (int k = 0; k < c->nlev; k++)
      if (!c->lev[k]) bad = 1;
    almz_img_free(c);
  }
  almz_img_free(src);
  return bad;
}

static int from_rgb8(void) {
  static uint8_t rgb[300 * 200 * 3];
  AlmzImg *img = almz_from_rgb8(rgb, 300, 200);
  if (img) almz_img_free(img);
  return 0;
}

static int mask_new(void) {
  AlmzMask *m = almz_mask_new(300, 200, 2, 0.5f);
  if (m) {
    if (!m->paint || !m->soft) return 1;
    almz_mask_free(m);
  }
  return 0;
}

static int mask_feather(void) {
  int saved = fail_at;
  fail_at = -1;
  AlmzMask *m = almz_mask_new(300, 200, 1, 0.0f);
  const int base = n_allocs;
  fail_at = saved < 0 ? -1 : saved + base;
  almz_mask_stroke(m, 50, 50, 150, 100, 20, 0.5f, 1.0f, 0);
  almz_mask_set_feather(m, 8.0f);
  fail_at = -1;
  almz_mask_free(m);
  return 0;
}

static float tile[3 * 64 * 64], tile2[3 * 64 * 64];

static int unsharp(void) {
  for (int i = 0; i < 3 * 64 * 64; i++) tile[i] = (i % 7) / 7.0f;
  almz_tile_unsharp(tile, 64, 64, 1.5f, 1.0f);
  for (int i = 0; i < 3 * 64 * 64; i++)
    if (!(tile[i] >= 0.0f && tile[i] <= 1.0f)) return 1;
  return 0;
}

static int detail(void) {
  for (int i = 0; i < 3 * 64 * 64; i++) tile[i] = (i % 7) / 7.0f, tile2[i] = (i % 5) / 5.0f;
  almz_tile_detail(tile2, tile, 64, 64, 1.5f, 0.5f);
  for (int i = 0; i < 3 * 64 * 64; i++)
    if (!(tile2[i] >= 0.0f && tile2[i] <= 1.0f)) return 1;
  return 0;
}

static int load_png_file(void) {
  int saved = fail_at;
  fail_at = -1;
  AlmzImg *img = almz_img_new(100, 80);
  char path[] = "/tmp/almaz_alloc_test_XXXXXX.png";
  int fd = mkstemps(path, 4);
  close(fd);
  char *err = NULL;
  almz_save(img, NULL, 0, NULL, 0, 0, path, ALMZ_FMT_PNG16, 90, -1, ALMZ_PROF_NONE, NULL, &err);
  almz_img_free(img);
  free(err);
  err = NULL;
  const int base = n_allocs;
  fail_at = saved < 0 ? -1 : saved + base;
  AlmzImg *back = almz_load(path, &err);
  fail_at = -1;
  if (back) almz_img_free(back);
  free(err);
  unlink(path);
  return 0;
}

static int set_err_msg(void) {
  char *err = NULL;
  set_err(&err, "boom");
  free(err); /* NULL when the copy failed: fine */
  return 0;
}

struct test { const char *name; scenario run; };

int main(void) {
  const struct test tests[] = {
      {"img_new", img_new},     {"img_copy", img_copy}, {"from_rgb8", from_rgb8},
      {"mask_new", mask_new},   {"mask_feather", mask_feather},
      {"unsharp", unsharp},     {"detail", detail},     {"load_png", load_png_file},
      {"set_err", set_err_msg},
  };
  int failures = 0;
  for (size_t t = 0; t < sizeof tests / sizeof *tests; t++) {
    const int total = count_allocs(tests[t].run);
    for (int i = 0; i < total; i++) {
      fail_at = i;
      n_allocs = 0;
      if (tests[t].run()) {
        printf("FAIL %s: allocation %d of %d failing left a broken result\n", tests[t].name, i, total);
        failures++;
      }
    }
    fail_at = -1;
    printf("ok   %s (%d allocations, each failed in turn)\n", tests[t].name, total);
  }
  printf(failures ? "%d failure(s)\n" : "all passed\n", failures);
  return failures ? 1 : 0;
}
