/* almazimg.h — pixel engine for almaz: 16-bit images with a mip pyramid,
 * low-resolution feathered masks, layer-stack compositing, viewport rendering,
 * tiled-inference helpers, classic filters, estimators and file I/O.
 *
 * Everything that touches every pixel lives here; the Clojure side only
 * orchestrates. All functions are plain C so jolt.ffi binds them directly.
 *
 * License: GPL-2.0-or-later. */
#ifndef ALMAZIMG_H
#define ALMAZIMG_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ALMZ_MAX_LEV 10

/* An RGB image, 16 bits per channel, interleaved, display-encoded (sRGB-ish).
 * lev[0] is full resolution; lev[k] is lev[k-1] box-filtered by 2. */
typedef struct AlmzImg {
  int w, h, nlev;
  int lw[ALMZ_MAX_LEV], lh[ALMZ_MAX_LEV];
  uint16_t *lev[ALMZ_MAX_LEV];
  uint64_t uid, rev; /* unique per image; bumped on every pixel change (GPU caches) */
} AlmzImg;

/* A layer mask at 1/div of the image resolution. `paint` holds what the brush
 * wrote (0..1); `soft` is `paint` blurred by the feather radius, and is what
 * compositing samples (bilinearly), so mask edges always blend smoothly. */
typedef struct AlmzMask {
  int iw, ih;        /* image size the mask covers */
  int w, h, div;     /* mask resolution */
  float feather;     /* gaussian sigma, image pixels */
  float *paint;
  float *soft;
  uint64_t uid, rev; /* as AlmzImg's */
  int32_t dirty;     /* changed since almz_mask_take_dirty: the image rect below */
  int32_t dx0, dy0, dx1, dy1;
} AlmzMask;

/* One entry of a layer stack: the filter's output image, its mask (NULL =
 * everywhere) and opacity. Layer i blends over the composite of layers < i:
 *   c = c + (img - c) * opacity * mask
 * An adjustment layer has no image; its colour transform of the composite
 * below takes img's place, in this order (each part optional):
 *   lut     3 x ALMZ_TONE_N per-channel curves (r, then g, then b) over 0..1
 *   saturation  scales the colour about its luma
 *   cube    cube_n^3 RGB triples (r index fastest): an RGB -> RGB map of
 *           display values 0..1, interpolated trilinearly
 * `radial` = {cx, cy, rx, ry, inner, falloff} (source pixels) further weights
 * the blend by distance from (cx, cy) in radii d: 0 within `inner`, rising as
 * a raised cosine to 1 at inner + falloff (darktable's vignette). rx <= 0:
 * no radial weight. */
typedef struct AlmzLayer {
  const AlmzImg *img;
  const AlmzMask *mask;
  float opacity;
  int32_t enabled;
  const float *lut;
  float saturation;
  int32_t cube_n;
  const float *cube;
  float radial[6];
} AlmzLayer;

/* Light adjustments. exposure in EV (linear light x 2^EV); the rest -1..1,
 * 0 = neutral. All work on sRGB-encoded values. */
typedef struct AlmzTone {
  float exposure, contrast, highlights, shadows, whites, blacks, saturation;
} AlmzTone;

#define ALMZ_TONE_N 4096
/* Tabulate the tone curve of `t` (everything but saturation) over 0..1. */
void almz_tone_lut(const AlmzTone *t, float *lut);
/* Apply `t` to one sRGB colour (0..1) in place. */
void almz_tone_apply(const AlmzTone *t, float *rgb);

/* 256-bin histograms of the composite (r, g, b, then Rec. 709 luma, each 256
 * counts) from the finest pyramid level of at most ~1.5 MP, within the
 * source rect `clip` {x, y, w, h} (NULL: all of it). */
void almz_histogram(const AlmzImg *base, const AlmzLayer *layers, int nlayers,
                    const int32_t *clip, uint32_t *out);
/* Draw `hist` (almz_histogram's) as overlapping RGB areas into the RGBA8
 * w x h buffer `out`, over `bg`. */
void almz_histogram_draw(const uint32_t *hist, int w, int h, uint8_t *out, const uint8_t *bg);

/* Bumped whenever a struct the caller fills (AlmzLayer, AlmzTone, AlmzMeta)
 * or a function's arguments change shape: a caller built against another
 * layout must not run. */
#define ALMZ_ABI 4
int almz_abi(void);

/* -- images -------------------------------------------------------------- */
AlmzImg *almz_img_new(int w, int h);                  /* zeroed */
AlmzImg *almz_img_wrap(int w, int h, uint16_t *data); /* takes ownership (malloc'd) */
AlmzImg *almz_img_copy(const AlmzImg *src);
void almz_img_free(AlmzImg *img);
int almz_img_width(const AlmzImg *img);
int almz_img_height(const AlmzImg *img);
/* Rebuild the pyramid over the level-0 rectangle (x, y, w, h). */
void almz_img_pyr_update(AlmzImg *img, int x, int y, int w, int h);
/* Read one level-0 pixel as floats 0..1 (tests, pickers). */
void almz_img_get(const AlmzImg *img, int x, int y, float *rgb);
/* Write the level-0 pixels from interleaved RGB floats 0..1 (tests). */
void almz_img_set_f32(AlmzImg *img, const float *rgb);

/* -- masks --------------------------------------------------------------- */
AlmzMask *almz_mask_new(int img_w, int img_h, int div, float value);
void almz_mask_free(AlmzMask *m);
void almz_mask_fill(AlmzMask *m, float value);
void almz_mask_invert(AlmzMask *m);
void almz_mask_set_feather(AlmzMask *m, float sigma_img_px);
/* Paint (erase = 0) or erase (erase = 1) a soft round stroke from (x0, y0) to
 * (x1, y1) in image pixels. hardness 0..1 is the solid core's share of the
 * radius; flow 0..1 caps the stroke's value. */
void almz_mask_stroke(AlmzMask *m, double x0, double y0, double x1, double y1,
                      double radius, double hardness, double flow, int erase);
/* The largest feathered value over the image rectangle (x, y, w, h). */
float almz_mask_max(const AlmzMask *m, int x, int y, int w, int h);
/* The feathered value at image coordinates (bilinear). */
float almz_mask_sample(const AlmzMask *m, double x, double y);

/* The image rect {x, y, w, h} changed since the last call (1), or 0 when
 * nothing has; either way it starts over. add_dirty marks a rect again (a
 * job that took it and didn't finish). */
int almz_mask_take_dirty(AlmzMask *m, int32_t *rect);
void almz_mask_add_dirty(AlmzMask *m, int x, int y, int w, int h);
/* An independent copy (for undo), and putting one back into a mask of the
 * same size (0; -1 when the sizes differ). */
AlmzMask *almz_mask_copy(const AlmzMask *m);
int almz_mask_restore(AlmzMask *dst, const AlmzMask *src);
/* The rects (x, y, w, h, image px) around the mask's connected areas over
 * `thr`, grown by `margin` and clamped to the image, overlapping ones
 * merged; at most `max`, returns how many. */
int almz_mask_regions(const AlmzMask *m, float thr, int margin, int32_t *rects, int max);
/* 1 where the mask is over `thr` at the pixels of the rect (x0, y0, w, h). */
void almz_mask_hole(const AlmzMask *m, int x0, int y0, int w, int h, float thr, uint8_t *out);

/* -- segmentation (automatic masks) --------------------------------------- */
/* The composite of base + layers stretched over n x n, as planar NCHW floats
 * (v - mean[c]) / std[c] of the 0..1 values (mean/std may be NULL: 0 / 1):
 * a segmentation model's input. 0 on success. */
int almz_seg_input(const AlmzImg *base, const AlmzLayer *layers, int nlayers, int n,
                   const float *mean, const float *std, float *out);
/* Set the mask's paint from a pw x ph map covering the whole image
 * (bilinear; `logits`: pass it through a sigmoid first), then feather. */
void almz_mask_set(AlmzMask *m, const float *prob, int pw, int ph, int logits);

/* -- repair ---------------------------------------------------------------- */
/* Fill the pixels where hole != 0 of a planar RGB (0..1) w x h image from
 * the rest of it (inpaint.c: PatchMatch texture, healed lighting) into
 * `out`; other pixels are copied. Deterministic for `seed`. 0 on success,
 * -1 when it can't (too small, out of memory: `out` is then the input). */
int almz_inpaint(const float *src, int w, int h, const uint8_t *hole, unsigned seed, float *out);

/* -- rendering ----------------------------------------------------------- */
/* Render the layer stack into the RGBA8 rectangle (rx, ry, rw, rh) of `out`
 * (out_w x out_h, tightly packed). `xf` {a, b, c, d, e, f} maps a screen
 * point to source pixels: ix = a sx + b sy + c, iy = d sx + e sy + f (the
 * view, orientation and crop in one). Pixels mapping outside the source rect
 * `clip` {x, y, w, h} (NULL: the image) get `bg` (RGBA). `overlay` (may be
 * NULL) tints the feathered mask with tint[0..2] at strength tint[3]. */
void almz_render(const AlmzImg *base, const AlmzLayer *layers, int nlayers,
                 const double *xf, const int32_t *clip,
                 const AlmzMask *overlay, const float *tint,
                 uint8_t *out, int out_w, int out_h,
                 int rx, int ry, int rw, int rh, const uint8_t *bg);

/* Render on the GPU (Metal, macOS) when it can, else on the CPU; the two
 * agree to within rounding. Exports, histograms and tiles always use the
 * CPU. Off: almz_render always draws on the CPU (tests compare the two). */
void almz_gpu_set_enabled(int on);
/* 1 when renders go to the GPU (enabled and a Metal device is present). */
int almz_gpu_active(void);

/* Write the full-resolution composite of base + layers into `dst` (same size). */
void almz_composite_into(AlmzImg *dst, const AlmzImg *base, const AlmzLayer *layers, int nlayers);

/* -- lens correction ------------------------------------------------------ */
/* Rows [y0, y1) of `dst` from `src` (same size, distinct) undistorted by
 * radial tables (almaz.lens): a corrected pixel at distance r from the
 * centre (cx, cy) is read, per channel c, from the source at the centre plus
 * k[c * n + i(r)] times its offset, where the n entries of each channel's
 * table cover r in 0..rmax (linearly interpolated, ends held); bicubic,
 * clamped at the edges. `vig` (n gains over source radius 0..vrmax, may be
 * NULL) then brightens it in linear light. Updates the pyramid there. */
void almz_lens_warp(AlmzImg *dst, const AlmzImg *src, double cx, double cy, double rmax, int n,
                    const float *k, double vrmax, const float *vig, int y0, int y1);

/* -- tiled inference ----------------------------------------------------- */
/* The composite of base + layers[0..n) over the tile (x0, y0, tw, th) as
 * planar NCHW floats 0..1. Outside the image the edge is mirrored. */
void almz_tile_extract(const AlmzImg *base, const AlmzLayer *layers, int nlayers,
                       int x0, int y0, int tw, int th, float *dst);
/* Blend a planar tile into `dst` at (x0, y0). On each side with a positive
 * ramp the tile fades in over that many pixels (crossfading with what a
 * finished neighbour already wrote); elsewhere it overwrites. Then updates
 * the pyramid under the tile. */
void almz_tile_write(AlmzImg *dst, const float *src, int tw, int th, int x0, int y0,
                     int ramp_l, int ramp_t, int ramp_r, int ramp_b);
/* Classic unsharp mask, in place on a planar tile. */
void almz_tile_unsharp(float *t, int tw, int th, float sigma, float amount);
/* Gaussian blur of a planar tile, in place (RawTherapee's recursive
 * Young-van Vliet filter; sigma in pixels, < 0.25 does nothing). */
void almz_tile_gauss(float *t, int tw, int th, float sigma);
/* Local contrast (RawTherapee's, i.e. clarity) of a planar tile, in place:
 * L* + (L* - blur_sigma(L*)) * amount, the boost scaled by `light` where it
 * brightens and `dark` where it darkens; colour follows the luminance.
 * A negative amount softens. */
void almz_tile_local_contrast(float *t, int tw, int th, float sigma, float amount, float dark, float light);

/* Focus over a whole image: dst = src with local contrast `clarity` (blur
 * radius `radius` px, as almz_tile_local_contrast) then a gaussian blur of
 * sigma `blur` px (as almz_tile_gauss), edges replicated. On the GPU when
 * there is one (returns 1), else on the CPU (0); -1 for mismatched images. */
int almz_focus(AlmzImg *dst, const AlmzImg *src, float clarity, float radius, float blur);
/* Put back `amount` of the input's fine detail the model removed:
 * out += amount * (highpass(in) - highpass(out)). */
void almz_tile_detail(float *out, const float *in, int tw, int th, float sigma, float amount);

/* -- estimators (autopilot) ---------------------------------------------- */
/* Noise sigma (0..1 units, Immerkaer) of the luminance over a central window
 * of level 0, and a sharpness score (mean gradient energy on edges at level 1,
 * relative to local contrast). */
void almz_estimate(const AlmzImg *img, float *noise, float *sharpness);

/* -- photo metadata (EXIF) ------------------------------------------------ */
/* The shooting metadata almaz carries from a source file to its exports.
 * Empty strings / zero numbers mean "unknown" and are not written. */
typedef struct AlmzMeta {
  char make[64], model[64], lens[128];
  char taken[20];        /* EXIF "YYYY:MM:DD HH:MM:SS" */
  double exposure;       /* seconds */
  double fnumber;
  double focal;          /* mm */
  int32_t iso, focal35, orientation;
} AlmzMeta;

/* Read the metadata of a JPEG (APP1), PNG (eXIf) or any TIFF-structured
 * file (TIFF, NEF, ARW, DNG, CR2, ...). 1 when any field was found. */
int almz_meta_read(const char *path, AlmzMeta *out);
/* The lens corrections a Sony raw file carries (DistortionCorrParams,
 * ChromaticAberrationCorrParams, VignettingCorrParams), each as stored: a
 * count, then the values; at most `cap` ints of each. 1 when present. */
int almz_sony_corrections(const char *path, int32_t *dist, int32_t *ca, int32_t *vig, int cap);
/* Serialize as a little-endian EXIF TIFF structure (no "Exif\0\0" prefix).
 * Returns the length; *out is malloc'd. 0 when there is nothing to write. */
size_t almz_exif_build(const AlmzMeta *m, uint8_t **out);

/* -- integrity ---------------------------------------------------------- */
/* SHA-256 of a file as 64 lowercase hex chars (+ NUL) into `hex`. 1 on
 * success, 0 when the file can't be read. */
int almz_sha256_file(const char *path, char *hex);

/* -- file I/O ------------------------------------------------------------ */
enum { ALMZ_FMT_PNG16 = 1, ALMZ_FMT_PNG8, ALMZ_FMT_JPEG, ALMZ_FMT_TIFF16 };
/* Load a JPEG / PNG / TIFF (by content). NULL on error (*err malloc'd). */
AlmzImg *almz_load(const char *path, char **err);
AlmzImg *almz_load_jpeg_mem(const uint8_t *data, size_t len, char **err);
AlmzImg *almz_from_rgb8(const uint8_t *rgb, int w, int h);
/* Colour spaces an export can be written in. The engine works in sRGB; the
 * others are converted into and their ICC profile embedded. NONE writes
 * sRGB values untagged. */
enum { ALMZ_PROF_NONE = 0, ALMZ_PROF_SRGB, ALMZ_PROF_P3, ALMZ_PROF_ADOBE };
/* Write the composite of base + layers at full resolution, streaming rows,
 * with `meta` (may be NULL) as EXIF. quality is JPEG quality 1..100;
 * compression is zlib's level 0..9 for PNG, and 0 none / 1 LZW / 2 Deflate
 * for TIFF (< 0: the format's default); profile is an ALMZ_PROF_*. The
 * output is out_w x out_h; `xf` maps its pixel centres to source pixels
 * (orientation and crop; NULL with out_w/out_h 0: the image as is). 0 on
 * success. */
int almz_save(const AlmzImg *base, const AlmzLayer *layers, int nlayers,
              const double *xf, int out_w, int out_h,
              const char *path, int format, int quality, int compression, int profile,
              const AlmzMeta *meta, char **err);
/* The size in bytes almz_save would write with the same arguments, from
 * encoding a few bands of rows spread over the image (all of it when
 * small). -1 on error. */
long long almz_save_estimate(const AlmzImg *base, const AlmzLayer *layers, int nlayers,
                             const double *xf, int out_w, int out_h,
                             int format, int quality, int compression, int profile,
                             const AlmzMeta *meta, char **err);
void almz_free_string(char *s);

#ifdef __cplusplus
}
#endif
#endif
