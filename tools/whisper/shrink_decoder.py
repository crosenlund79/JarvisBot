"""Make the sherpa-onnx Whisper decoder smaller and faster for on-phone use.

1. The self-attention cache length (fixed 448 in the export) becomes a free dimension, so the app can pass a shorter
   cache; every token then copies far less memory (ScatterND / Split / Transpose on the cache dominate decoding time).
2. The float32 token-embedding table (the bulk of the file) is stored as int8 with one scale per row and expanded by a
   DequantizeLinear node, which ONNX Runtime folds back into a constant at load time
   (session config "session.disable_quant_qdq" = "1").
"""
import sys
import numpy as np
import onnx
from onnx import helper, numpy_helper, TensorProto

src, dst = sys.argv[1], sys.argv[2]
m = onnx.load(src)
g = m.graph

for vi in list(g.input) + list(g.output):
    if 'self_k_cache' in vi.name or 'self_v_cache' in vi.name:
        d = vi.type.tensor_type.shape.dim[2]
        d.ClearField('dim_value')
        d.dim_param = 'n_ctx'
del g.value_info[:]

name = 'textDecoder.token_embedding.weight'
init = next(t for t in g.initializer if t.name == name)
w = numpy_helper.to_array(init).astype(np.float32)
scale = np.maximum(np.abs(w).max(axis=1), 1e-8) / 127.0
q = np.clip(np.round(w / scale[:, None]), -127, 127).astype(np.int8)
g.initializer.remove(init)
g.initializer.extend([numpy_helper.from_array(q, name + '_int8'), numpy_helper.from_array(scale.astype(np.float32), name + '_scale')])
g.node.insert(0, helper.make_node('DequantizeLinear', [name + '_int8', name + '_scale'], [name], name='token_embedding_dequant', axis=0))
err = np.abs(q * scale[:, None] - w).max()
onnx.save(m, dst)
print(f'{dst}: max embedding quantisation error {err:.5f} (weights |max| {np.abs(w).max():.3f})')
