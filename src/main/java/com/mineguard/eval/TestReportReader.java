package com.mineguard.eval;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.*;

@Component
public class TestReportReader {
    public Result read(Path directory) {
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        if (!Files.isDirectory(directory)) return new Result(0, 0, 0, 0, 0);
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml")).toList()) {
                Element suite = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(path.toFile()).getDocumentElement();
                tests += integer(suite, "tests"); failures += integer(suite, "failures");
                errors += integer(suite, "errors"); skipped += integer(suite, "skipped");
            }
            return new Result(tests, tests - failures - errors - skipped, failures, errors, skipped);
        } catch (Exception ex) {
            throw new IllegalStateException("cannot read surefire reports", ex);
        }
    }
    private int integer(Element element, String name) { return Integer.parseInt(element.getAttribute(name)); }
    public record Result(int testCount, int passed, int failures, int errors, int skipped) {}
}
