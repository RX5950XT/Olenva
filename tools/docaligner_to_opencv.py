"""Make DocAligner's heatmap model loadable by OpenCV DNN (the app's only inference engine).

Source: https://github.com/DocsaidLab/DocAligner (Apache-2.0), model lcnet100_h_e_bifpn_256_fp32.onnx.
OpenCV cannot parse its BiFPN Einsum('i,i...->...') weighted sums, so constants are folded and each Einsum
becomes Mul + ReduceSum. The script checks the result against onnxruntime (max diff ~4e-7).

Usage: pip install onnx onnxruntime opencv-python
       python tools/docaligner_to_opencv.py lcnet100_h_e_bifpn_256_fp32.onnx app/src/main/assets/docaligner_lcnet100.onnx
"""
import sys, onnx, numpy as np, onnxruntime as ort, cv2
from onnx import helper, numpy_helper

src, dst = sys.argv[1], sys.argv[2]
so = ort.SessionOptions(); so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
so.optimized_model_filepath = dst + '.tmp.onnx'
ort.InferenceSession(src, so, providers=['CPUExecutionProvider'])
m = onnx.load(dst + '.tmp.onnx')
g = m.graph
inits = {i.name: i for i in g.initializer}
nodes = []
for n in g.node:
    if n.op_type != 'Einsum':
        nodes.append(n); continue
    w, x = n.input
    assert w in inits, w
    wv = numpy_helper.to_array(inits[w]).reshape(-1, 1, 1, 1, 1).astype(np.float32)
    g.initializer.append(numpy_helper.from_array(wv, w + '_r'))
    axes = numpy_helper.from_array(np.array([0], np.int64), n.output[0] + '_axes'); g.initializer.append(axes)
    nodes.append(helper.make_node('Mul', [x, w + '_r'], [n.output[0] + '_m']))
    nodes.append(helper.make_node('ReduceSum', [n.output[0] + '_m', axes.name], [n.output[0]], keepdims=0))
del g.node[:]; g.node.extend(nodes)
onnx.save(m, dst)
import os; os.remove(dst + '.tmp.onnx')

x = np.random.rand(1, 3, 256, 256).astype(np.float32)
ref = ort.InferenceSession(src, providers=['CPUExecutionProvider']).run(None, {'img': x})[0]
net = cv2.dnn.readNetFromONNX(dst); net.setInput(x); out = net.forward()
print('max diff vs original', float(np.abs(out - ref).max()), out.shape)
