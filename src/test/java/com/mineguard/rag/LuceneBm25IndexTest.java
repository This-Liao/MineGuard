package com.mineguard.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuceneBm25IndexTest {
    @Test
    void 可命中中文术语摄像头编号和算法ID() {
        try (var index = new LuceneBm25Index()) {
            index.replaceAll(List.of(
                    chunk("helmet", "个体防护", "进入作业区必须正确佩戴安全帽。"),
                    chunk("camera", "视频设备", "camera-19 离线时检查交换机端口。"),
                    chunk("algorithm", "算法管理", "intrusion_detection 算法用于周界入侵检测。")));

            assertThat(index.search("安全帽佩戴要求", 3).getFirst().chunk().documentId()).isEqualTo("helmet");
            assertThat(index.search("排查 CAMERA-19", 3).getFirst().chunk().documentId()).isEqualTo("camera");
            assertThat(index.search("intrusion_detection", 3).getFirst().chunk().documentId()).isEqualTo("algorithm");
            assertThat(index.size()).isEqualTo(3);
        }
    }

    @Test
    void 拒绝重复文档块ID且原索引仍可查询() {
        try (var index = new LuceneBm25Index()) {
            index.replaceAll(List.of(chunk("old", "原索引", "安全帽")));
            assertThatThrownBy(() -> index.replaceAll(List.of(
                    chunk("a", "A", "摄像头"), new DocumentChunk("b", "B", "a-chunk", "算法"))))
                    .hasMessageContaining("唯一");
            assertThat(index.search("安全帽", 1).getFirst().chunk().documentId()).isEqualTo("old");
        }
    }

    private DocumentChunk chunk(String id, String title, String content) {
        return new DocumentChunk(id, title, id + "-chunk", content);
    }
}
