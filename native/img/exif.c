/* exif.c — read and write the shooting metadata almaz carries to exports
 * (camera, lens, exposure, aperture, ISO, focal length, capture time,
 * orientation). See AlmzMeta in almazimg.h. GPL-2.0-or-later.
 *
 * Reading walks the TIFF structure (IFD0 and its EXIF sub-IFD) of a TIFF-based
 * file (TIFF, NEF, ARW, DNG, CR2, ...), of a JPEG's APP1 "Exif" segment, or of
 * a PNG's eXIf chunk. Writing builds a minimal little-endian EXIF TIFF
 * structure with just those fields. */
#include "almazimg.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ---- random access over a file or a memory block ------------------------ */
typedef struct {
  FILE *f;
  const uint8_t *mem;
  long base, len; /* the TIFF structure's start and size within the source */
} src_t;

static int read_at(const src_t *s, long off, void *dst, size_t n) {
  if (off < 0 || off + (long)n > s->len) return 0;
  if (s->mem) {
    memcpy(dst, s->mem + s->base + off, n);
    return 1;
  }
  return fseek(s->f, s->base + off, SEEK_SET) == 0 && fread(dst, 1, n, s->f) == n;
}

typedef struct {
  src_t src;
  int le; /* little-endian */
} tiff_t;

static uint32_t u16(const tiff_t *t, const uint8_t *b) {
  return t->le ? (uint32_t)(b[0] | b[1] << 8) : (uint32_t)(b[0] << 8 | b[1]);
}
static uint32_t u32(const tiff_t *t, const uint8_t *b) {
  return t->le ? (uint32_t)b[0] | (uint32_t)b[1] << 8 | (uint32_t)b[2] << 16 | (uint32_t)b[3] << 24
               : (uint32_t)b[0] << 24 | (uint32_t)b[1] << 16 | (uint32_t)b[2] << 8 | (uint32_t)b[3];
}

/* T_IFD: an offset to a sub-IFD; libtiff 4.5 writes the EXIF pointer with it
 * where 4.7 writes T_LONG, and other writers use either. */
enum { T_BYTE = 1, T_ASCII = 2, T_SHORT = 3, T_LONG = 4, T_RATIONAL = 5, T_UNDEF = 7, T_SSHORT = 8,
       T_SLONG = 9, T_SRATIONAL = 10, T_IFD = 13 };

typedef struct {
  uint32_t tag, type, count, value_off; /* value_off: offset of the value (inline or pointed) */
} entry_t;

static int type_size(uint32_t type) {
  switch (type) {
  case T_BYTE: case T_ASCII: case T_UNDEF: return 1;
  case T_SHORT: case T_SSHORT: return 2;
  case T_LONG: case T_SLONG: case T_IFD: return 4;
  case T_RATIONAL: case T_SRATIONAL: return 8;
  default: return 0;
  }
}

/* Read the IFD at `off`: up to `max` entries. Returns how many. */
static int read_ifd(const tiff_t *t, long off, entry_t *out, int max) {
  uint8_t b[12];
  if (!read_at(&t->src, off, b, 2)) return 0;
  const int n = (int)u16(t, b);
  int k = 0;
  for (int i = 0; i < n && k < max; i++) {
    const long eo = off + 2 + 12L * i;
    if (!read_at(&t->src, eo, b, 12)) break;
    entry_t e = {u16(t, b), u16(t, b + 2), u32(t, b + 4), 0};
    const long size = (long)type_size(e.type) * e.count;
    if (!size) continue;
    e.value_off = size <= 4 ? (uint32_t)(eo + 8) : u32(t, b + 8);
    out[k++] = e;
  }
  return k;
}

static void get_ascii(const tiff_t *t, const entry_t *e, char *dst, size_t cap) {
  if (e->type != T_ASCII && e->type != T_UNDEF) return;
  size_t n = e->count < cap - 1 ? e->count : cap - 1;
  if (!read_at(&t->src, e->value_off, dst, n)) n = 0;
  dst[n] = 0;
  /* trim trailing spaces and NULs */
  while (n && (dst[n - 1] == ' ' || dst[n - 1] == 0)) dst[--n] = 0;
}

static double get_num(const tiff_t *t, const entry_t *e) {
  uint8_t b[8];
  switch (e->type) {
  case T_SHORT: return read_at(&t->src, e->value_off, b, 2) ? u16(t, b) : 0;
  case T_LONG:
  case T_IFD: return read_at(&t->src, e->value_off, b, 4) ? u32(t, b) : 0;
  case T_RATIONAL:
  case T_SRATIONAL: {
    if (!read_at(&t->src, e->value_off, b, 8)) return 0;
    const double num = e->type == T_SRATIONAL ? (int32_t)u32(t, b) : u32(t, b);
    const double den = e->type == T_SRATIONAL ? (int32_t)u32(t, b + 4) : u32(t, b + 4);
    return den ? num / den : 0;
  }
  default: return 0;
  }
}

/* Parse the TIFF structure in `src` into `m`. 1 when anything was found. */
static int parse_tiff(src_t src, AlmzMeta *m) {
  tiff_t t = {src, 1};
  uint8_t h[8];
  if (!read_at(&t.src, 0, h, 8)) return 0;
  if (h[0] == 'I' && h[1] == 'I') t.le = 1;
  else if (h[0] == 'M' && h[1] == 'M') t.le = 0;
  else return 0;
  if (u16(&t, h + 2) != 42) return 0;
  entry_t es[256];
  int found = 0;
  long exif_off = 0;
  const int n = read_ifd(&t, u32(&t, h + 4), es, 256);
  for (int i = 0; i < n; i++) {
    const entry_t *e = &es[i];
    switch (e->tag) {
    case 0x010F: get_ascii(&t, e, m->make, sizeof m->make); found = 1; break;
    case 0x0110: get_ascii(&t, e, m->model, sizeof m->model); found = 1; break;
    case 0x0112: m->orientation = (int32_t)get_num(&t, e); found = 1; break;
    case 0x8769: exif_off = (long)get_num(&t, e); break;
    }
  }
  if (exif_off) {
    const int k = read_ifd(&t, exif_off, es, 256);
    for (int i = 0; i < k; i++) {
      const entry_t *e = &es[i];
      switch (e->tag) {
      case 0x829A: m->exposure = get_num(&t, e); found = 1; break;
      case 0x829D: m->fnumber = get_num(&t, e); found = 1; break;
      case 0x8827: m->iso = (int32_t)get_num(&t, e); found = 1; break;
      case 0x9003: get_ascii(&t, e, m->taken, sizeof m->taken); found = 1; break;
      case 0x920A: m->focal = get_num(&t, e); found = 1; break;
      case 0xA405: m->focal35 = (int32_t)get_num(&t, e); found = 1; break;
      case 0xA434: get_ascii(&t, e, m->lens, sizeof m->lens); found = 1; break;
      }
    }
  }
  return found;
}

/* ---- embedded lens corrections ---------------------------------------- */
/* Element i of an integer array entry (signed per its type). */
static int32_t get_int(const tiff_t *t, const entry_t *e, uint32_t i) {
  uint8_t b[4];
  const int sz = type_size(e->type);
  if (i >= e->count || !read_at(&t->src, e->value_off + (long)i * sz, b, sz)) return 0;
  switch (e->type) {
  case T_SHORT: return (int32_t)u16(t, b);
  case T_SSHORT: return (int16_t)u16(t, b);
  case T_LONG: return (int32_t)u32(t, b);
  case T_SLONG: case T_IFD: return (int32_t)u32(t, b);
  default: return 0;
  }
}

/* Sony's VignettingCorrParams (0x7032), ChromaticAberrationCorrParams
 * (0x7035) and DistortionCorrParams (0x7037) from the IFD at `off`, as
 * darktable reads them (Exif.SubImage1.*, or IFD0 after a DNG round trip).
 * 1 when all three are there. */
static int sony_tags(const tiff_t *t, long off, int32_t *dist, int32_t *ca, int32_t *vig, int cap) {
  entry_t es[256];
  const entry_t *d = NULL, *c = NULL, *v = NULL;
  const int n = read_ifd(t, off, es, 256);
  for (int i = 0; i < n; i++)
    switch (es[i].tag) {
    case 0x7032: v = &es[i]; break;
    case 0x7035: c = &es[i]; break;
    case 0x7037: d = &es[i]; break;
    }
  if (!d || !c || !v) return 0;
  for (int i = 0; i < cap; i++) {
    dist[i] = get_int(t, d, (uint32_t)i);
    ca[i] = get_int(t, c, (uint32_t)i);
    vig[i] = get_int(t, v, (uint32_t)i);
  }
  return 1;
}

int almz_sony_corrections(const char *path, int32_t *dist, int32_t *ca, int32_t *vig, int cap) {
  FILE *f = fopen(path, "rb");
  if (!f) return 0;
  fseek(f, 0, SEEK_END);
  src_t s = {f, NULL, 0, ftell(f)};
  tiff_t t = {s, 1};
  uint8_t h[8];
  int found = 0;
  if (read_at(&t.src, 0, h, 8) && ((h[0] == 'I' && h[1] == 'I') || (h[0] == 'M' && h[1] == 'M'))) {
    t.le = h[0] == 'I';
    const long ifd0 = u32(&t, h + 4);
    found = sony_tags(&t, ifd0, dist, ca, vig, cap);
    if (!found) {
      entry_t es[256];
      const int n = read_ifd(&t, ifd0, es, 256);
      for (int i = 0; i < n && !found; i++)
        if (es[i].tag == 0x014A) /* SubIFDs: SubImage1 is the first */
          found = sony_tags(&t, (long)(uint32_t)get_int(&t, &es[i], 0), dist, ca, vig, cap);
    }
  }
  fclose(f);
  return found;
}

/* ---- containers --------------------------------------------------------- */
static int jpeg_meta(FILE *f, long len, AlmzMeta *m) {
  long off = 2;
  uint8_t b[10];
  while (off + 4 <= len) {
    if (fseek(f, off, SEEK_SET) || fread(b, 1, 4, f) != 4 || b[0] != 0xFF) return 0;
    const int marker = b[1];
    const long seg = (long)(b[2] << 8 | b[3]);
    if (marker == 0xDA || marker == 0xD9) return 0; /* image data: no more metadata */
    if (marker == 0xE1 && seg >= 8 && fread(b, 1, 6, f) == 6 && !memcmp(b, "Exif\0\0", 6)) {
      src_t s = {f, NULL, off + 10, seg - 8};
      return parse_tiff(s, m);
    }
    off += 2 + seg;
  }
  return 0;
}

static int png_meta(FILE *f, long len, AlmzMeta *m) {
  long off = 8;
  uint8_t b[8];
  while (off + 8 <= len) {
    if (fseek(f, off, SEEK_SET) || fread(b, 1, 8, f) != 8) return 0;
    const long n = (long)((uint32_t)b[0] << 24 | b[1] << 16 | b[2] << 8 | b[3]);
    if (!memcmp(b + 4, "eXIf", 4)) {
      src_t s = {f, NULL, off + 8, n};
      return parse_tiff(s, m);
    }
    if (!memcmp(b + 4, "IDAT", 4) || !memcmp(b + 4, "IEND", 4)) return 0;
    off += 12 + n;
  }
  return 0;
}

int almz_meta_read(const char *path, AlmzMeta *out) {
  memset(out, 0, sizeof *out);
  FILE *f = fopen(path, "rb");
  if (!f) return 0;
  fseek(f, 0, SEEK_END);
  const long len = ftell(f);
  uint8_t sig[8] = {0};
  fseek(f, 0, SEEK_SET);
  const size_t got = fread(sig, 1, 8, f);
  int found = 0;
  if (got >= 2 && sig[0] == 0xFF && sig[1] == 0xD8)
    found = jpeg_meta(f, len, out);
  else if (got == 8 && !memcmp(sig, "\x89PNG\r\n\x1a\n", 8))
    found = png_meta(f, len, out);
  else {
    src_t s = {f, NULL, 0, len};
    found = parse_tiff(s, out);
  }
  fclose(f);
  return found;
}

/* ---- building ----------------------------------------------------------- */
typedef struct {
  uint16_t tag, type;
  uint32_t count;
  uint8_t data[160]; /* the value bytes, little-endian */
} out_entry;

static void put16(uint8_t *b, uint32_t v) { b[0] = v & 0xFF; b[1] = v >> 8 & 0xFF; }
static void put32(uint8_t *b, uint32_t v) { put16(b, v & 0xFFFF); put16(b + 2, v >> 16); }

static int add_ascii(out_entry *es, int n, uint16_t tag, const char *s) {
  const size_t len = strlen(s);
  if (!len || len + 1 > sizeof es[n].data) return n;
  es[n] = (out_entry){tag, T_ASCII, (uint32_t)(len + 1), {0}};
  memcpy(es[n].data, s, len + 1);
  return n + 1;
}

static int add_short(out_entry *es, int n, uint16_t tag, int v) {
  if (v <= 0 || v > 65535) return n;
  es[n] = (out_entry){tag, T_SHORT, 1, {0}};
  put16(es[n].data, (uint32_t)v);
  return n + 1;
}

static int add_long(out_entry *es, int n, uint16_t tag, uint32_t v) {
  es[n] = (out_entry){tag, T_LONG, 1, {0}};
  put32(es[n].data, v);
  return n + 1;
}

/* A rational for `v`: 1/x exactly for sub-second shutter speeds that are
 * whole reciprocals, else fixed precision. */
static int add_rational(out_entry *es, int n, uint16_t tag, double v, uint32_t precision) {
  if (!(v > 0)) return n;
  uint32_t num, den;
  const double r = 1.0 / v;
  if (v < 1.0 && fabs(r - round(r)) < 1e-6 * r) {
    num = 1;
    den = (uint32_t)round(r);
  } else {
    num = (uint32_t)round(v * precision);
    den = precision;
  }
  es[n] = (out_entry){tag, T_RATIONAL, 1, {0}};
  put32(es[n].data, num);
  put32(es[n].data + 4, den);
  return n + 1;
}

static size_t entry_size(const out_entry *e) { return (size_t)type_size(e->type) * e->count; }

/* Write an IFD at `off` into buf (entries sorted by tag), its out-of-line
 * values right after it. Returns the offset past everything written. */
static size_t write_ifd(uint8_t *buf, size_t off, const out_entry *es, int n) {
  put16(buf + off, (uint32_t)n);
  size_t data = off + 2 + 12 * (size_t)n + 4;
  for (int i = 0; i < n; i++) {
    uint8_t *e = buf + off + 2 + 12 * (size_t)i;
    const size_t size = entry_size(&es[i]);
    put16(e, es[i].tag);
    put16(e + 2, es[i].type);
    put32(e + 4, es[i].count);
    if (size <= 4) {
      memcpy(e + 8, es[i].data, size);
    } else {
      put32(e + 8, (uint32_t)data);
      memcpy(buf + data, es[i].data, size);
      data += size + (size & 1); /* values start on word boundaries */
    }
  }
  put32(buf + off + 2 + 12 * (size_t)n, 0); /* no next IFD */
  return data;
}

static size_t ifd_size(const out_entry *es, int n) {
  size_t s = 2 + 12 * (size_t)n + 4;
  for (int i = 0; i < n; i++) {
    const size_t z = entry_size(&es[i]);
    if (z > 4) s += z + (z & 1);
  }
  return s;
}

size_t almz_exif_build(const AlmzMeta *m, uint8_t **out) {
  out_entry ifd0[8], exif[10];
  int n0 = 0, ne = 0;
  /* EXIF IFD, ascending tags */
  ne = add_rational(exif, ne, 0x829A, m->exposure, 1000000);
  ne = add_rational(exif, ne, 0x829D, m->fnumber, 10);
  ne = add_short(exif, ne, 0x8827, m->iso);
  if (ne || m->taken[0] || m->focal > 0 || m->lens[0]) {
    exif[ne] = (out_entry){0x9000, T_UNDEF, 4, {'0', '2', '3', '2'}};
    ne++;
  }
  ne = add_ascii(exif, ne, 0x9003, m->taken);
  ne = add_rational(exif, ne, 0x920A, m->focal, 10);
  ne = add_short(exif, ne, 0xA405, m->focal35);
  ne = add_ascii(exif, ne, 0xA434, m->lens);
  /* IFD0, ascending tags */
  n0 = add_ascii(ifd0, n0, 0x010F, m->make);
  n0 = add_ascii(ifd0, n0, 0x0110, m->model);
  n0 = add_short(ifd0, n0, 0x0112, m->orientation);
  if (!n0 && !ne) {
    *out = NULL;
    return 0;
  }
  n0 = add_ascii(ifd0, n0, 0x0131, "Almaz");
  if (ne) n0 = add_long(ifd0, n0, 0x8769, 0); /* patched below */
  const size_t s0 = ifd_size(ifd0, n0);
  const size_t total = 8 + s0 + (ne ? ifd_size(exif, ne) : 0);
  uint8_t *buf = (uint8_t *)calloc(1, total);
  memcpy(buf, "II*\0", 4);
  put32(buf + 4, 8);
  if (ne) put32(ifd0[n0 - 1].data, (uint32_t)(8 + s0));
  write_ifd(buf, 8, ifd0, n0);
  if (ne) write_ifd(buf, 8 + s0, exif, ne);
  *out = buf;
  return total;
}
