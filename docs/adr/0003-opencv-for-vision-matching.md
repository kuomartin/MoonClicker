# OpenCV for on-device template matching; ML Kit OCR not committed

VisionEngine uses OpenCV (native, via JNI) for template matching (`match.*`), confirmed implemented against real `cv::matchTemplate` calls, not a stub — see `docs/research/vision-ocr-status.md`. ML Kit OCR was scoped as a possible future capability but has zero code, zero dependency, and no committed timeline; treat any "OCR is coming" claim as aspirational, not planned.

## Status

Accepted (vision matching). OCR: not started, no roadmap commitment.
