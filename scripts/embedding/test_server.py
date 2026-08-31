"""仅测试 HTTP 契约，不下载模型；真实推理另由独立 Retrieval Eval 验收。"""
import json
import threading
import unittest
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from server import MODEL_ID, handler_for


class FakeEncoder:
    metadata = {"model": MODEL_ID, "dimensions": 512}

    def encode(self, texts):
        if texts == ["触发故障"]:
            raise RuntimeError("内部敏感信息")
        return [[1.0] + [0.0] * 511 for _ in texts], len(texts) * 3


class ServerContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), handler_for(FakeEncoder()))
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(2)

    def request(self, method, path, body=None):
        connection = HTTPConnection(*self.server.server_address, timeout=3)
        try:
            connection.request(method, path, json.dumps(body) if body is not None else None)
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def test_health_and_unknown_path(self):
        status, body = self.request("GET", "/health")
        self.assertEqual((200, "UP", 512), (status, body["status"], body["dimensions"]))
        self.assertEqual(404, self.request("POST", "/embeddings", {})[0])
        self.assertEqual(404, self.request("GET", "/missing")[0])

    def test_order_dimensions_usage_and_single_input(self):
        for texts in (["查询一", "查询二"], "单条查询"):
            status, body = self.request("POST", "/v1/embeddings", {"model": MODEL_ID, "input": texts})
            count = len(texts) if isinstance(texts, list) else 1
            self.assertEqual(200, status)
            self.assertEqual(list(range(count)), [row["index"] for row in body["data"]])
            self.assertTrue(all(len(row["embedding"]) == 512 for row in body["data"]))
            self.assertEqual(count * 3, body["usage"]["total_tokens"])

    def test_rejects_invalid_parameters(self):
        for invalid in ({"input": []}, {"input": [" "]}, {"input": [1]}, {"input": ["x"] * 33},
                        {"input": "x" * 16001}, {"model": "unknown"}, {"dimensions": 768}, {"encoding_format": "base64"}):
            body = {"model": MODEL_ID, "input": "有效查询", **invalid}
            self.assertEqual(400, self.request("POST", "/v1/embeddings", body)[0])

    def test_failure_does_not_fall_back_or_leak_details(self):
        status, body = self.request("POST", "/v1/embeddings", {"model": MODEL_ID, "input": "触发故障"})
        self.assertEqual(503, status)
        self.assertNotIn("内部敏感信息", str(body))
        self.assertNotIn("data", body)


if __name__ == "__main__":
    unittest.main()
