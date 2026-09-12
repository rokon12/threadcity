package ca.bazlur.threadcity.application;

import ca.bazlur.threadcity.analysis.ThreadDumpAnalyzer;
import ca.bazlur.threadcity.domain.AnalysisResult;
import ca.bazlur.threadcity.parser.HotSpotThreadDumpParser;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Application-level entry point for parsing and analyzing thread dumps.
 */
@Service
public final class ThreadDumpAnalysisService {

    private final HotSpotThreadDumpParser parser = new HotSpotThreadDumpParser();
    private final ThreadDumpAnalyzer analyzer = new ThreadDumpAnalyzer();

    public AnalysisResult analyze(String sourceName, String content) {
        return analyzer.analyze(parser.parse(sourceName, content));
    }

    public AnalysisResult analyzeSample(String sourceName, String fixtureName) {
        return analyze(sourceName, loadSample(fixtureName));
    }

    private String loadSample(String name) {
        try (InputStream stream = ThreadDumpAnalysisService.class.getResourceAsStream("/samples/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Built-in incident sample is missing: " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load the built-in incident: " + name, exception);
        }
    }
}
