package com.letsblog.api.benchmark;

import com.letsblog.api.markdown.MarkdownRenderer;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@Fork(value = 1, warmups = 0)
@Warmup(iterations = 3)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
public class MarkdownRendererBenchmark {

    private MarkdownRenderer renderer;

    private String simpleMarkdown;
    private String complexMarkdown;
    private String markdownWithTable;

    @Setup(Level.Trial)
    public void setup() {
        renderer = new MarkdownRenderer();

        simpleMarkdown = "# Hello\n\nThis is a **bold** and *italic* text.";

        complexMarkdown = "# Complex Document\n\n" +
                "## Section 1\n\n" +
                "This is a paragraph with **bold**, *italic*, and `code` formatting.\n\n" +
                "### Subsection 1.1\n\n" +
                "- Item 1\n" +
                "- Item 2\n" +
                "- Item 3\n\n" +
                "## Section 2\n\n" +
                "1. First\n" +
                "2. Second\n" +
                "3. Third\n\n" +
                "[Link](https://example.com)\n\n" +
                "```java\n" +
                "public class Example {\n" +
                "  public static void main(String[] args) {\n" +
                "    System.out.println(\"Hello\");\n" +
                "  }\n" +
                "}\n" +
                "```";

        markdownWithTable = "# Table Example\n\n" +
                "| Header 1 | Header 2 | Header 3 |\n" +
                "|----------|----------|----------|\n" +
                "| Cell 1   | Cell 2   | Cell 3   |\n" +
                "| Cell 4   | Cell 5   | Cell 6   |\n" +
                "| Cell 7   | Cell 8   | Cell 9   |";
    }

    @Benchmark
    public void benchmarkSimpleMarkdown(Blackhole bh) {
        String result = renderer.render(simpleMarkdown);
        bh.consume(result);
    }

    @Benchmark
    public void benchmarkComplexMarkdown(Blackhole bh) {
        String result = renderer.render(complexMarkdown);
        bh.consume(result);
    }

    @Benchmark
    public void benchmarkMarkdownWithTable(Blackhole bh) {
        String result = renderer.render(markdownWithTable);
        bh.consume(result);
    }

    @Benchmark
    public void benchmarkEmptyMarkdown(Blackhole bh) {
        String result = renderer.render("");
        bh.consume(result);
    }

    @Benchmark
    public void benchmarkNullMarkdown(Blackhole bh) {
        String result = renderer.render(null);
        bh.consume(result);
    }
}
