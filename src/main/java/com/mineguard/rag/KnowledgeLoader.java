package com.mineguard.rag;

import com.mineguard.config.MineGuardProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Component
public class KnowledgeLoader {
    private final Path path;

    public KnowledgeLoader(MineGuardProperties properties) {
        this.path = Path.of(properties.knowledgePath()).toAbsolutePath().normalize();
    }

    public List<KnowledgeDocument> load() {
        if (!Files.isDirectory(path)) throw new IllegalStateException("knowledge directory not found: " + path);
        try (Stream<Path> files = Files.list(path)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .map(this::read)
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("cannot load knowledge documents", ex);
        }
    }

    private KnowledgeDocument read(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            String id = file.getFileName().toString().replaceFirst("\\.md$", "");
            String title = content.lines().filter(line -> line.startsWith("# ")).findFirst()
                    .map(line -> line.substring(2).trim()).orElse(id);
            if (!content.contains("Synthetic Demo Data")) {
                throw new IllegalStateException("knowledge document lacks Synthetic Demo Data notice: " + file);
            }
            return new KnowledgeDocument(id, title, content, true);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read knowledge document: " + file, ex);
        }
    }
}
