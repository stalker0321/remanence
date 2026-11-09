"""Make the bundled YuNet ONNX accept any input size.

Android runs `face_detection_yunet` through OpenCV's FaceDetectorYN, which
reshapes the network per pass (640 and 960 long edge). onnxruntime-web cannot
reshape a fixed 1x3x640x640 input, so the site's copy marks the input height
and width, and each output's anchor count, as symbolic. Weights and nodes are
untouched: every Reshape uses -1 and both Resizes use scales.

Usage: python yunet_dynamic.py <android yunet.onnx> <out yunet.onnx>
"""
import sys

import onnx

model = onnx.load(sys.argv[1])
graph = model.graph
dims = graph.input[0].type.tensor_type.shape.dim
dims[2].ClearField("dim_value")
dims[2].dim_param = "height"
dims[3].ClearField("dim_value")
dims[3].dim_param = "width"
for output in graph.output:
    anchors = output.type.tensor_type.shape.dim[1]
    anchors.ClearField("dim_value")
    anchors.dim_param = f"{output.name}_anchors"
del graph.value_info[:]
onnx.checker.check_model(model)
onnx.save(model, sys.argv[2])
