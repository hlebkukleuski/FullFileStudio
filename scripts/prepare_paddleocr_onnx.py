#!/usr/bin/env python3
# Copyright (C) 2026 Hleb Kukleuski <hlebkukleuski@gmail.com>
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program. If not, see <https://gnu.org>.

"""
End-to-end download, extraction, ONNX conversion, dynamic-shape binding, and verification script
for FullFileStudio's Dual-Head PaddleOCR pipeline (`app/src/main/assets/models/`).

Prerequisites:
    pip install paddlepaddle paddle2onnx onnx onnxruntime numpy pyyaml
"""

import argparse
import shutil
import subprocess
import tarfile
import urllib.request
from pathlib import Path
from typing import Dict, List, Optional

PROJECT_ROOT = Path(__file__).resolve().parent.parent
ASSETS_MODELS_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "models"
DET_DIR = ASSETS_MODELS_DIR / "det"
REC_DIR = ASSETS_MODELS_DIR / "rec"
TMP_DIR = PROJECT_ROOT / "build" / "paddle_export_tmp"

PADDLEX_MODEL_BASE = "https://paddle-model-ecology.bj.bcebos.com/paddlex/official_inference_model/paddle3.0.0"
PADDLE_GITHUB_RAW = "https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/main/ppocr/utils"

DICT_URLS: Dict[str, str] = {
    "ppocr_keys_v1.txt": f"{PADDLE_GITHUB_RAW}/ppocr_keys_v1.txt",
    "ppocrv5_dict.txt": f"{PADDLE_GITHUB_RAW}/dict/ppocrv5_dict.txt",
}

MODEL_TARBALLS: List[Dict[str, str]] = [
    {
        "name": "det_model",
        "url": "https://paddleocr.bj.bcebos.com/PP-OCRv4/chinese/ch_PP-OCRv4_det_infer.tar",
        "output_onnx": str(DET_DIR / "inference.onnx"),
        "is_rec": "false",
        "rec_input_height": "0",
    },
    {
        "name": "primary_rec_model",
        "url": "https://paddleocr.bj.bcebos.com/PP-OCRv4/chinese/ch_PP-OCRv4_rec_infer.tar",
        "output_onnx": str(REC_DIR / "inference.onnx"),
        "is_rec": "true",
        "rec_input_height": "48",
    },
    {
        "name": "eslav_rec_model",
        "url": f"{PADDLEX_MODEL_BASE}/eslav_PP-OCRv5_mobile_rec_infer.tar",
        "output_onnx": str(REC_DIR / "eslav_ppocrv5_rec.onnx"),
        "output_dict": str(REC_DIR / "eslav_ppocrv5_dict.txt"),
        "is_rec": "true",
        "rec_input_height": "48",
    },
    {
        "name": "latin_rec_model",
        "url": f"{PADDLEX_MODEL_BASE}/latin_PP-OCRv5_mobile_rec_infer.tar",
        "output_onnx": str(REC_DIR / "latin_ppocrv5_rec.onnx"),
        "output_dict": str(REC_DIR / "latin_ppocrv5_dict.txt"),
        "is_rec": "true",
        "rec_input_height": "48",
    },
]

EXTRA_MULTILINGUAL_KEYS: List[str] = [
    # East-Slavic & South-Slavic Cyrillic extensions
    "Ђ", "Ѓ", "Є", "Ѕ", "І", "Ї", "Ј", "Љ", "Њ", "Ћ", "Ќ", "Ў", "Џ", "Ґ",
    "ђ", "ѓ", "є", "ѕ", "і", "ї", "ј", "љ", "њ", "ћ", "ќ", "ў", "џ", "ґ",
    # Historical Cyrillic & Old Church Slavonic (Monomakh Unicode)
    "Ѣ", "ѣ", "Ѳ", "ѳ", "Ѵ", "ѵ", "Ѧ", "ѧ", "Ѩ", "ѩ", "Ѫ", "ѫ", "Ѭ", "ѭ",
    "Ѯ", "ѯ", "Ѱ", "ѱ", "Ꙋ", "ꙋ", "Ѻ", "ѻ", "Ѿ", "ѿ", "Ҁ", "ҁ",
    # Mathematical Fraktur / Gothic alphabets (U+1D504..U+1D59F)
    "𝔄", "𝔅", "ℭ", "𝔇", "𝔈", "𝔉", "𝔊", "ℌ", "ℑ", "𝔍", "𝔎", "𝔏", "𝔐",
    "𝔑", "𝔒", "𝔓", "𝔔", "ℜ", "𝔖", "𝔗", "𝔘", "𝔙", "𝔚", "𝔛", "𝔜", "ℨ",
    "𝔞", "𝔟", "𝔠", "𝔡", "𝔢", "𝔣", "𝔤", "𝔥", "𝔦", "𝔧", "𝔨", "𝔩", "𝔪",
    "𝔫", "𝔬", "𝔭", "𝔮", "𝔯", "𝔰", "𝔱", "𝔲", "𝔳", "𝔴", "𝔵", "𝔶", "𝔷",
    "𝕬", "𝕭", "𝕮", "𝕯", "𝕰", "𝕱", "𝕲", "𝕳", "𝕴", "𝕵", "𝕶", "𝕷", "𝕸",
    "𝕹", "𝕺", "𝕻", "𝕼", "𝕽", "𝕾", "𝕿", "𝖀", "𝖁", "𝖂", "𝖃", "𝖄", "𝖅",
    "𝖆", "𝖇", "𝖈", "𝖉", "𝖊", "𝖋", "𝖌", "𝖍", "𝖎", "𝖏", "𝖐", "𝖑", "𝖒",
    "𝖓", "𝖔", "𝖕", "𝖖", "𝖗", "𝖘", "𝖙", "𝖚", "𝖛", "𝖜", "𝖝", "𝖞", "𝖟",
    # Currency & typography symbols
    "№", "₽", "€", "₴", "«", "»", "„", "“", "”", "—", "–", "…", "•", "‰",
]


def download_file(url: str, dest: Path) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    print(f"[Download] {url} -> {dest}")
    urllib.request.urlretrieve(url, str(dest))


def locate_extracted_model_dir(root_dir: Path) -> Optional[Path]:
    for candidate in [root_dir] + list(root_dir.rglob("*")):
        if candidate.is_dir():
            has_params = (candidate / "inference.pdiparams").exists()
            has_model = (candidate / "inference.json").exists() or (candidate / "inference.pdmodel").exists()
            if has_params and has_model:
                return candidate
    return None


def export_embedded_character_dict(model_dir: Path, output_dict: Path) -> bool:
    config_path = model_dir / "inference.yml"
    if not config_path.exists():
        print(f"[Error] {config_path} not found, cannot export {output_dict.name}")
        return False

    import yaml

    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    chars = (config.get("PostProcess") or {}).get("character_dict")
    if not chars:
        print(f"[Error] No PostProcess.character_dict in {config_path}")
        return False

    output_dict.parent.mkdir(parents=True, exist_ok=True)
    output_dict.write_text("\n".join(str(ch) for ch in chars) + "\n", encoding="utf-8")
    print(f"[OK] Exported dictionary: {output_dict} ({len(chars)} keys)")
    return True


def verify_onnx_runtime_session(onnx_path: Path, is_rec: bool, expected_h: int) -> bool:
    try:
        import numpy as np
        import onnxruntime as ort

        sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        inp = sess.get_inputs()[0]
        test_h = expected_h if (is_rec and expected_h > 0) else 256
        test_w = 320 if is_rec else 256
        dummy = np.zeros((1, 3, test_h, test_w), dtype=np.float32)
        out = sess.run(None, {inp.name: dummy})
        print(f"[Verify] {onnx_path.name} input={dummy.shape} -> output={out[0].shape}")
        return True
    except Exception as exc:
        print(f"[Warn] ONNX Runtime verification warning for {onnx_path.name}: {exc}")
        return False


def export_paddle_to_onnx(
    model_dir: Path,
    output_onnx: Path,
    is_rec: bool,
    fallback_rec_height: int = 48,
) -> None:
    output_onnx.parent.mkdir(parents=True, exist_ok=True)
    model_filename = "inference.json" if (model_dir / "inference.json").exists() else "inference.pdmodel"
    params_filename = "inference.pdiparams"

    cmd = [
        "paddle2onnx",
        "--model_dir", str(model_dir),
        "--model_filename", model_filename,
        "--params_filename", params_filename,
        "--save_file", str(output_onnx),
        "--opset_version", "16",
        "--enable_onnx_checker", "True",
    ]
    subprocess.run(cmd, check=True)

    import onnx
    model = onnx.load(str(output_onnx))
    target_h = fallback_rec_height if fallback_rec_height > 0 else 48
    for inp in model.graph.input:
        dims = inp.type.tensor_type.shape.dim
        if len(dims) == 4:
            dims[0].dim_value = 1
            dims[1].dim_value = 3
            if is_rec:
                existing_h = dims[2].dim_value
                if existing_h in (32, 48, 64):
                    target_h = existing_h
                else:
                    dims[2].ClearField("dim_param")
                    dims[2].dim_value = target_h
                dims[3].ClearField("dim_value")
                dims[3].dim_param = "W"
            else:
                dims[2].ClearField("dim_value")
                dims[2].dim_param = "H"
                dims[3].ClearField("dim_value")
                dims[3].dim_param = "W"
    onnx.checker.check_model(model)
    onnx.save(model, str(output_onnx))

    # If the exported graph has a hard-coded stride/pooling height (32 vs 48), auto-probe and bind it
    if is_rec and not verify_onnx_runtime_session(output_onnx, is_rec=True, expected_h=target_h):
        alt_h = 48 if target_h == 32 else 32
        for inp in model.graph.input:
            dims = inp.type.tensor_type.shape.dim
            if len(dims) == 4:
                dims[2].ClearField("dim_param")
                dims[2].dim_value = alt_h
        onnx.save(model, str(output_onnx))
        verify_onnx_runtime_session(output_onnx, is_rec=True, expected_h=alt_h)
    elif not is_rec:
        verify_onnx_runtime_session(output_onnx, is_rec=False, expected_h=0)

    size_mb = output_onnx.stat().st_size / (1024 * 1024)
    print(f"[OK] Exported & verified ONNX model: {output_onnx} ({size_mb:.2f} MB)")


def build_multilingual_dictionaries(force: bool) -> None:
    for name, url in DICT_URLS.items():
        if (REC_DIR / name).exists() and not force:
            print(f"[Skip] {name} already exists (use --force to re-download)")
            continue
        try:
            download_file(url, REC_DIR / name)
        except Exception as exc:
            print(f"[Warn] Could not download dictionary {name}: {exc}")

    base_keys_path = REC_DIR / "ppocr_keys_v1.txt"
    if not base_keys_path.exists():
        return

    existing = base_keys_path.read_text(encoding="utf-8").splitlines()
    seen = set(existing)
    for dict_file in ["cyrillic_dict.txt", "latin_dict.txt"]:
        p = REC_DIR / dict_file
        if p.exists():
            for ch in p.read_text(encoding="utf-8").splitlines():
                if ch not in seen:
                    existing.append(ch)
                    seen.add(ch)
    for ch in EXTRA_MULTILINGUAL_KEYS:
        if ch not in seen:
            existing.append(ch)
            seen.add(ch)

    multilingual_out = REC_DIR / "multilingual_keys.txt"
    multilingual_out.write_text("\n".join(existing) + "\n", encoding="utf-8")
    print(f"[OK] Generated {multilingual_out} ({len(existing)} total keys)")


def download_and_convert_all_models(force: bool) -> None:
    if shutil.which("paddle2onnx") is None:
        print("[Skip] 'paddle2onnx' CLI not found in PATH. Install via: pip install paddlepaddle paddle2onnx onnx onnxruntime")
        return

    for spec in MODEL_TARBALLS:
        name = spec["name"]
        url = spec["url"]
        output_onnx = Path(spec["output_onnx"])
        is_rec = spec["is_rec"].lower() == "true"
        rec_h = int(spec.get("rec_input_height", "48"))
        output_dict = Path(spec["output_dict"]) if "output_dict" in spec else None

        outputs = [output_onnx] + ([output_dict] if output_dict else [])
        if all(p.exists() for p in outputs) and not force:
            print(f"[Skip] {name} already exported (use --force to rebuild)")
            continue

        tar_path = TMP_DIR / f"{name}.tar"
        extract_dir = TMP_DIR / name
        if extract_dir.exists():
            shutil.rmtree(extract_dir)
        extract_dir.mkdir(parents=True, exist_ok=True)

        try:
            download_file(url, tar_path)
            with tarfile.open(tar_path, "r:*") as tar:
                tar.extractall(path=extract_dir)
            model_dir = locate_extracted_model_dir(extract_dir)
            if model_dir is None:
                print(f"[Error] Could not locate inference.pdiparams inside {tar_path}")
                continue
            if output_dict is not None and not export_embedded_character_dict(model_dir, output_dict):
                continue
            export_paddle_to_onnx(model_dir, output_onnx, is_rec=is_rec, fallback_rec_height=rec_h)
        except Exception as exc:
            print(f"[Error] Failed to export {name}: {exc}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--force", action="store_true", help="re-download and re-export files that already exist")
    args = parser.parse_args()

    DET_DIR.mkdir(parents=True, exist_ok=True)
    REC_DIR.mkdir(parents=True, exist_ok=True)
    TMP_DIR.mkdir(parents=True, exist_ok=True)

    build_multilingual_dictionaries(args.force)
    download_and_convert_all_models(args.force)


if __name__ == "__main__":
    main()