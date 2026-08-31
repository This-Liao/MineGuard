"""本机 BGE 语义向量服务，提供 OpenAI-compatible /v1/embeddings。"""

import argparse
import hashlib
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODEL_ID = "BAAI/bge-small-zh-v1.5"
WEIGHTS_REPO = "Xenova/bge-small-zh-v1.5"
REVISION = "75c43b069aac4d136ba6bc1122f995fedcfd2781"
WEIGHTS_FILE = "onnx/model_quantized.onnx"
FILES = (WEIGHTS_FILE, "tokenizer.json", "config.json")


def prepare(directory):
    """下载固定版本，并独立核对模型 SHA-256 或 Git blob 摘要。"""
    from huggingface_hub import HfApi, hf_hub_download

    info = HfApi(token=False).model_info(WEIGHTS_REPO, revision=REVISION, files_metadata=True)
    metadata = {item.rfilename: item for item in info.siblings}
    records = {}
    for name in FILES:
        path = Path(hf_hub_download(WEIGHTS_REPO, name, revision=REVISION, local_dir=directory, token=False))
        data = path.read_bytes()
        sha256 = hashlib.sha256(data).hexdigest()
        item = metadata[name]
        if item.lfs:
            if sha256 != item.lfs.sha256:
                raise ValueError("模型文件 SHA-256 不匹配，禁止加载")
        else:
            blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
            if blob != item.blob_id:
                raise ValueError("模型配置 Git blob 摘要不匹配，禁止加载")
        records[name] = {"sha256": sha256, "bytes": len(data)}
    manifest = {"model": MODEL_ID, "weightsRepo": WEIGHTS_REPO, "revision": REVISION,
                "weightsFile": WEIGHTS_FILE, "quantization": "INT8", "pooling": "CLS + L2", "files": records}
    (directory / "verified-model.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


class Encoder:
    def __init__(self, directory):
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer

        self.np = np
        self.lock = threading.Lock()
        self.metadata = json.loads((directory / "verified-model.json").read_text(encoding="utf-8"))
        if self.metadata["revision"] != REVISION or self.metadata["weightsFile"] != WEIGHTS_FILE:
            raise ValueError("模型版本与服务不一致")
        for name in FILES:
            if hashlib.sha256((directory / name).read_bytes()).hexdigest() != self.metadata["files"][name]["sha256"]:
                raise ValueError("本地模型文件已变化，禁止加载")
        self.tokenizer = Tokenizer.from_file(str(directory / "tokenizer.json"))
        self.tokenizer.enable_truncation(max_length=512)
        self.tokenizer.enable_padding(pad_id=0, pad_token="[PAD]")
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        self.session = ort.InferenceSession(str(directory / WEIGHTS_FILE), sess_options=options, providers=["CPUExecutionProvider"])
        self.inputs = {item.name for item in self.session.get_inputs()}
        self.metadata.update({"dimensions": 512, "maxTokens": 512, "runtime": "onnxruntime-" + ort.__version__})

    def encode(self, texts):
        with self.lock:
            rows = self.tokenizer.encode_batch(texts)
            values = {
                "input_ids": self.np.asarray([row.ids for row in rows], dtype=self.np.int64),
                "attention_mask": self.np.asarray([row.attention_mask for row in rows], dtype=self.np.int64),
                "token_type_ids": self.np.asarray([row.type_ids for row in rows], dtype=self.np.int64),
            }
            hidden = self.session.run(None, {name: values[name] for name in self.inputs})[0]
            # BGE 使用首个 CLS token，不使用均值池化；随后做 L2 归一化。
            vectors = hidden[:, 0, :].astype(self.np.float32)
            norms = self.np.linalg.norm(vectors, axis=1, keepdims=True)
            if vectors.shape != (len(texts), 512) or not self.np.isfinite(vectors).all() or (norms == 0).any():
                raise ValueError("模型输出向量无效")
            return (vectors / norms).tolist(), int(values["attention_mask"].sum())


def handler_for(encoder):
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(15)

        def log_message(self, *_):
            # 不记录文本、请求头、凭据和逐个请求。
            pass

        def reply(self, status, value):
            payload = json.dumps(value, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(payload)

        def do_GET(self):
            if self.path == "/health":
                self.reply(200, {"status": "UP", **encoder.metadata})
            else:
                self.reply(404, {"error": "接口不存在"})

        def do_POST(self):
            if self.path != "/v1/embeddings":
                self.reply(404, {"error": "接口不存在"})
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if size < 1 or size > 2_000_000:
                    raise ValueError("请求体大小无效")
                body = json.loads(self.rfile.read(size))
                texts = body.get("input") if isinstance(body, dict) else None
                if isinstance(texts, str):
                    texts = [texts]
                if not isinstance(texts, list) or not 1 <= len(texts) <= 32:
                    raise ValueError("批次大小无效")
                if any(not isinstance(text, str) or not text.strip() or len(text) > 16000 for text in texts):
                    raise ValueError("文本无效")
                if body.get("model") != MODEL_ID or body.get("encoding_format", "float") != "float":
                    raise ValueError("模型或编码格式无效")
                if body.get("dimensions", 512) != 512:
                    raise ValueError("维度无效")
            except (ValueError, TypeError, TimeoutError):
                self.reply(400, {"error": "请求参数无效"})
                return
            try:
                vectors, tokens = encoder.encode(texts)
                self.reply(200, {"object": "list", "model": MODEL_ID,
                                 "data": [{"object": "embedding", "index": index, "embedding": vector} for index, vector in enumerate(vectors)],
                                 "usage": {"prompt_tokens": tokens, "total_tokens": tokens}})
            except Exception:
                self.reply(503, {"error": "语义推理失败；未降级为哈希向量"})
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, default=Path("data/runtime/semantic-embedding/model"))
    parser.add_argument("--port", type=int, default=18082)
    parser.add_argument("--download-only", action="store_true")
    args = parser.parse_args()
    if args.download_only:
        args.model_dir.mkdir(parents=True, exist_ok=True)
        print(json.dumps(prepare(args.model_dir), ensure_ascii=False, indent=2))
        return
    encoder = Encoder(args.model_dir)
    # 本地开发服务仅监听回环，不提供公网部署模式。
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler_for(encoder))
    server.daemon_threads = True
    print(f"BGE 语义向量服务已就绪：127.0.0.1:{args.port}，512 维，INT8 ONNX", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
