package ca.bazlur.threadcity.domain;

import java.util.List;

public record StackCluster(List<String> stackFrames, List<JavaThread> threads) {

    public StackCluster {
        stackFrames = List.copyOf(stackFrames);
        threads = List.copyOf(threads);
    }
}
