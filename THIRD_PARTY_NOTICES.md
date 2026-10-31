# Third-party components

The app processes captures and translations locally. Model weights are not original work of this project.

| Component | License / notice | Source |
|---|---|---|
| Flutter | BSD-3-Clause | https://github.com/flutter/flutter |
| AIHub Japanese → Korean translator | Model card: MIT; retain underlying model notices below | https://huggingface.co/sappho192/aihub-ja-ko-translator |
| Japanese BERT v2 pretrained weights | Model-card body: CC-BY-SA 3.0; metadata: CC-BY-SA 4.0. Version discrepancy requires clarification. Training code's Apache-2.0 license is separate. | https://huggingface.co/tohoku-nlp/bert-base-japanese-v2/raw/main/README.md |
| KoGPT2 base v2 | CC-BY-NC-SA 4.0 | https://github.com/SKT-AI/KoGPT2 |
| ONNX Runtime 1.31.0 | MIT | https://github.com/microsoft/onnxruntime |
| MeCab | BSD option selected (also offers LGPL/GPL alternatives) | https://github.com/taku910/mecab |
| UniDic-lite 1.0.8 / UniDic | Dictionary BSD option selected; AUTHORS retained | https://github.com/polm/unidic-lite |
| Google ML Kit Japanese OCR | Google SDK terms; includes third-party components | https://developers.google.com/ml-kit/terms |

Exact model revisions, file sizes, SHA-256 hashes, engine revision and dictionary source are pinned in `models/manifest.json`. The model card links AI-Hub training-data terms. Its MIT tag alone does not establish permission to disregard the underlying model notices. KoGPT2's noncommercial/share-alike terms and Japanese BERT's share-alike terms require review together. Public distribution, contest prize eligibility and commercial use have not been cleared by the rights holders.

The AIHub model card acknowledges Korean government/NIA-supported AI-Hub training data. AI-Hub's data-use/export restrictions and their applicability to separately trained downloadable weights must be checked separately; this project does not infer that source-code publication automatically exports the original training data.

Google's [ML Kit Terms & Privacy](https://developers.google.com/ml-kit/terms) state that input images/text and their outputs are processed on-device and are not sent to Google. They also describe SDK performance/utilization metrics and possible service contacts. Offline inference is not a guarantee of zero SDK network traffic. User-facing privacy disclosures must address the SDK's metrics as applicable; packet-level behavior has not been audited here.

Selected-component notices are maintained in Android assets `licenses/AIHub-JA-KO.txt` and `licenses/ONNX-MeCab-UniDic.txt`. ML Kit license metadata comes with Maven artifacts. These notices and license links do not substitute for rights-holder permission where it is required. The `1.0.1-beta.1` release rebuild includes the corrected Japanese BERT weight-license entry, the pinned AIHub revision, data acknowledgment and SDK network-activity explanation. `scripts/verify-apk.py` checks that both selected-component notice files in the APK exactly match the source files. Earlier comparison license texts may also remain in assets; their model weights and inference engines are excluded from the current APK.
