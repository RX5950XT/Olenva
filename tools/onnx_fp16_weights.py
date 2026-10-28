"""Store an ONNX model's float32 weights as float16 (half the file) while the graph still computes in float32.

Each weight constant is followed by a Cast back to float32, so the model stays valid ONNX and OpenCV DNN folds the
casts at load time. Small constants (scalars, shapes) are left alone. Used for assets/doc_ori_lcnet.onnx.

Usage: pip install onnx numpy
       python tools/onnx_fp16_weights.py inference.onnx app/src/main/assets/doc_ori_lcnet.onnx
"""
import sys, onnx, numpy as np
from onnx import helper, numpy_helper, TensorProto

src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
g = m.graph
assert not g.initializer, 'weights are expected in Constant nodes'
nodes = []
for n in g.node:
    t = n.attribute[0].t if n.op_type == 'Constant' and n.attribute[0].name == 'value' else None
    if t is None or t.data_type != TensorProto.FLOAT or np.prod(t.dims) < 16:
        nodes.append(n); continue
    out = n.output[0]
    half = numpy_helper.from_array(numpy_helper.to_array(t).astype(np.float16), out + '_f16')
    nodes.append(helper.make_node('Constant', [], [out + '_f16'], value=half))
    nodes.append(helper.make_node('Cast', [out + '_f16'], [out], to=TensorProto.FLOAT))
del g.node[:]; g.node.extend(nodes)
# Apache-2.0 §4(b): the file itself says it was changed.
onnx.helper.set_model_props(m, {'modified_by': 'Olenva tools/onnx_fp16_weights.py: weights stored as float16, graph unchanged'})
onnx.checker.check_model(m)
onnx.save(m, dst)
