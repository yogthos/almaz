#!/usr/bin/env python3
"""Convert the lensfun database (XML, version 2) into src/almaz/lens/db.clj.

The database is CC-BY-SA 3.0 (the lensfun project, https://lensfun.github.io);
the generated namespace keeps that notice. It holds one EDN string, read on
first use, with only what almaz.lens needs:

  {:mounts  {name [compat ...]}
   :cameras [{:maker [s ...] :model [s ...] :variant [s ...] :mount s :crop f}]
   :lenses  [{:maker [s ...] :model [s ...] :mounts [s ...] :type s
              :focal [min max] :aperture [min max] :center [x y]
              :crop f :aspect f
              :calibs [{:crop f :aspect f
                        :distortion [[model focal real-focal t0 t1 t2 t3 t4] ...]
                        :tca        [[model focal t0 .. t11] ...]
                        :vignetting [[model focal aperture distance t0 t1 t2] ...]}]}]}

Multi-language strings (maker, model, variant) keep every variant, the
untranslated one first, the way lensfun's lfMLstr does. Parsing follows
lensfun's database.cpp: a <calibration>'s attributes default to the lens's
<cropfactor> / <aspect-ratio> read before it; a distortion without a measured
real-focal gets lensfun's estimate from its terms.

Usage: native/tools/venv/bin/python native/tools/lensfun_to_clj.py ~/src/lensfun/data/db
"""
import os
import re
import sys
import xml.etree.ElementTree as ET


def num(s):
    return float(s)


def ml(elem, tag):
    """All language variants of <tag> under elem, untranslated first."""
    plain = [e.text.strip() for e in elem.findall(tag) if e.text and 'lang' not in e.attrib]
    langs = [e.text.strip() for e in elem.findall(tag) if e.text and 'lang' in e.attrib]
    return plain + [s for s in langs if s not in plain]


def aspect(text):
    if ':' in text:
        a, b = text.split(':')
        return num(a) / num(b)
    return num(text)


DIST_TERMS = {'a': 0, 'k1': 0, 'b': 1, 'k2': 1, 'c': 2, 'k3': 2, 'k4': 3, 'k5': 4}
TCA_TERMS = {'kr': 0, 'vr': 0, 'alpha0': 0, 'kb': 1, 'vb': 1, 'beta0': 1,
             'cr': 2, 'alpha1': 2, 'cb': 3, 'beta1': 3, 'br': 4, 'alpha2': 4,
             'bb': 5, 'beta2': 5, 'alpha3': 6, 'beta3': 7, 'alpha4': 8,
             'beta4': 9, 'alpha5': 10, 'beta5': 11}
VIG_TERMS = {'k1': 0, 'alpha1': 0, 'k2': 1, 'alpha2': 1, 'k3': 2, 'alpha3': 2}


def distortion(e):
    t = [0.0] * 5
    model = e.get('model')
    focal = num(e.get('focal', '0'))
    real = num(e.get('real-focal', '0'))
    for k, v in e.attrib.items():
        if k in DIST_TERMS:
            t[DIST_TERMS[k]] = num(v)
    if real <= 0:
        if model == 'ptlens':
            real = focal * (1 - t[0] - t[1] - t[2])
        elif model == 'poly3':
            real = focal * (1 - t[0])
        else:
            real = focal
    return [model, focal, real] + t


def tca(e):
    t = [0.0] * 12
    t[0] = t[1] = 1.0
    for k, v in e.attrib.items():
        if k in TCA_TERMS:
            t[TCA_TERMS[k]] = num(v)
    return [e.get('model'), num(e.get('focal', '0'))] + t


def vignetting(e):
    t = [0.0] * 3
    for k, v in e.attrib.items():
        if k in VIG_TERMS:
            t[VIG_TERMS[k]] = num(v)
    return [e.get('model'), num(e.get('focal', '0')), num(e.get('aperture', '0')),
            num(e.get('distance', '0'))] + t


def lens(e):
    out = {'maker': ml(e, 'maker'), 'model': ml(e, 'model'),
           'mounts': [m.text.strip() for m in e.findall('mount')],
           'type': 'rectilinear', 'crop': 1.0, 'aspect': 1.5,
           'focal': [0.0, 0.0], 'aperture': [0.0, 0.0], 'center': [0.0, 0.0],
           'calibs': []}
    attr = {'crop': 1.0, 'aspect': 1.5}
    for c in e:
        tag = c.tag
        if tag == 'cropfactor':
            out['crop'] = attr['crop'] = num(c.text)
        elif tag == 'aspect-ratio':
            out['aspect'] = attr['aspect'] = aspect(c.text.strip())
        elif tag == 'type':
            out['type'] = c.text.strip()
        elif tag in ('focal', 'aperture'):
            lo, hi = out[tag]
            if 'value' in c.attrib:
                lo = hi = num(c.get('value'))
            lo = num(c.get('min')) if 'min' in c.attrib else lo
            hi = num(c.get('max')) if 'max' in c.attrib else hi
            out[tag] = [lo, hi]
        elif tag == 'center':
            out['center'] = [num(c.get('x', '0')), num(c.get('y', '0'))]
        elif tag == 'calibration':
            cal = {'crop': num(c.get('cropfactor', attr['crop'])),
                   'aspect': num(c.get('aspect-ratio', attr['aspect'])),
                   'distortion': [], 'tca': [], 'vignetting': []}
            for k in c:
                if k.tag == 'distortion':
                    cal['distortion'].append(distortion(k))
                elif k.tag == 'tca':
                    cal['tca'].append(tca(k))
                elif k.tag == 'vignetting':
                    cal['vignetting'].append(vignetting(k))
            out['calibs'].append(cal)
    return out


def camera(e):
    crop = e.find('cropfactor')
    mount = e.find('mount')
    return {'maker': ml(e, 'maker'), 'model': ml(e, 'model'), 'variant': ml(e, 'variant'),
            'mount': mount.text.strip() if mount is not None else None,
            'crop': num(crop.text) if crop is not None else 0.0}


def edn(x):
    if x is None:
        return 'nil'
    if isinstance(x, bool):
        return 'true' if x else 'false'
    if isinstance(x, float):
        r = repr(x)
        return r[:-2] if r.endswith('.0') and 'e' not in r else r
    if isinstance(x, int):
        return str(x)
    if isinstance(x, str):
        return '"' + x.replace('\\', '\\\\').replace('"', '\\"') + '"'
    if isinstance(x, list):
        return '[' + ' '.join(edn(v) for v in x) + ']'
    if isinstance(x, dict):
        # keyword keys for our fields, string keys for names (mounts)
        key = lambda k: ':' + k if re.fullmatch(r'[a-z-]+', k) and x is not MOUNTS else edn(k)
        return '{' + ' '.join(key(k) + ' ' + edn(v) for k, v in x.items()) + '}'
    raise TypeError(x)


MOUNTS = {}


def main(db_dir, out_path):
    mounts, cameras, lenses = MOUNTS, [], []
    for name in sorted(os.listdir(db_dir)):
        if not name.endswith('.xml'):
            continue
        root = ET.parse(os.path.join(db_dir, name)).getroot()
        for m in root.findall('mount'):
            mounts[m.find('name').text.strip()] = [c.text.strip() for c in m.findall('compat')]
        cameras += [camera(c) for c in root.findall('camera')]
        lenses += [lens(l) for l in root.findall('lens')]
    data = edn({'mounts': mounts, 'cameras': cameras, 'lenses': lenses})
    body = data.replace('\\', '\\\\').replace('"', '\\"')
    with open(out_path, 'w') as f:
        f.write(';; GENERATED by native/tools/lensfun_to_clj.py from the lensfun database\n'
                ';; (https://github.com/lensfun/lensfun, data/db). Do not edit.\n'
                ';; The lensfun database is licensed CC-BY-SA 3.0 by the lensfun contributors.\n'
                '(ns almaz.lens.db\n  "The lensfun camera and lens database as EDN (see almaz.lens).")\n\n'
                '(def edn\n  "')
        f.write(body)
        f.write('")\n')
    print(f'{len(cameras)} cameras, {len(lenses)} lenses -> {out_path} ({os.path.getsize(out_path) // 1024} KB)')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else 'src/almaz/lens/db.clj')
