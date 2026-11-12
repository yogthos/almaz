#!/usr/bin/env python3
"""Export UperNet + ConvNeXt-tiny (openmmlab/upernet-convnext-tiny on Hugging
Face; MIT), an ADE20K semantic segmentation model, to ONNX for almaz's
scene masks (Sky, Portrait, Landscape).

The graph takes "image", NCHW float32 RGB in [0,1] at SIZE x SIZE (ImageNet
normalization is folded in), and gives "groups", 1 x 3 x SIZE x SIZE: the
probability that each pixel is sky, a person, or natural landscape (the
ADE20K classes summed per group, below), so the app needs no class table.

Usage:
  native/tools/venv/bin/python native/tools/export_scene_seg.py models/almaz/upernet-convnext-tiny-512.onnx [--size 512]
"""

import argparse

import torch
import torch.nn as nn
from transformers import UperNetForSemanticSegmentation

MODEL = "openmmlab/upernet-convnext-tiny"

# ADE20K class names (the model's id2label) per group.
GROUPS = {
    "sky": ["sky"],
    "person": ["person"],
    "landscape": ["tree", "grass", "mountain", "plant", "water", "sea", "field", "sand", "rock",
                  "hill", "river", "flower", "palm", "land", "waterfall", "lake", "dirt track",
                  "earth", "snow"],
}


class Groups(nn.Module):
    def __init__(self, model, index):
        super().__init__()
        self.model = model
        self.register_buffer("mean", torch.tensor([0.485, 0.456, 0.406]).view(1, 3, 1, 1))
        self.register_buffer("std", torch.tensor([0.229, 0.224, 0.225]).view(1, 3, 1, 1))
        n = model.config.num_labels
        m = torch.zeros(len(index), n)
        for g, ids in enumerate(index):
            m[g, ids] = 1.0
        self.register_buffer("membership", m)

    def forward(self, image):
        logits = self.model(pixel_values=(image - self.mean) / self.std).logits
        probs = torch.softmax(logits, dim=1)
        return torch.einsum("gc,bchw->bghw", self.membership, probs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("output")
    ap.add_argument("--size", type=int, default=512)
    args = ap.parse_args()

    model = UperNetForSemanticSegmentation.from_pretrained(MODEL).eval()
    by_name = {v.strip().lower(): int(k) for k, v in model.config.id2label.items()}
    index = []
    for g, names in GROUPS.items():
        ids = [by_name[n] for n in names if n in by_name]
        missing = [n for n in names if n not in by_name]
        if missing:
            print(f"{g}: not in the label set: {missing}")
        index.append(ids)
        print(g, ids)

    net = Groups(model, index).eval()
    x = torch.rand(1, 3, args.size, args.size)
    with torch.no_grad():
        torch.onnx.export(net, (x,), args.output, input_names=["image"], output_names=["groups"],
                          opset_version=18, dynamo=True, external_data=False)
    print("wrote", args.output)


if __name__ == "__main__":
    main()
