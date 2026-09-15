package ca.bazlur.threadcity.lab;

import java.time.Duration;

/**
 * Starts intentionally broken JVM workloads for local diagnostic practice.
 */
public final class ThreadCityFailureLab {

    private ThreadCityFailureLab() {
    }

    public static void main(String[] arguments) {
        try {
            Duration duration = parseDuration(arguments);
            try (FailureLab lab = new FailureLab()) {
                lab.start();
                long pid = ProcessHandle.current().pid();
                System.out.println("ThreadCity Failure Lab is active");
                System.out.println("PID: " + pid);
                System.out.println("Scenarios: deadlock, lock convoy, executor starvation, connection-pool exhaustion,"
                        + " socket stall, CPU spin, virtual-thread pressure");
                System.out.println("Capture command:");
                System.out.println("java -jar ../threadcity-collector/target/threadcity-collector-0.1.0-SNAPSHOT.jar"
                        + " --pid " + pid + " --duration 10 --snapshots 3 --output threadcity-lab.threadcity");
                System.out.println("Lab exits after " + duration.toSeconds() + " seconds. Press Ctrl+C to stop sooner.");
                Thread.sleep(duration);
            }
        } catch (IllegalArgumentException exception) {
            System.err.println("Error: " + exception.getMessage());
            System.err.println("Usage: java -jar threadcity-failure-lab.jar [--duration 120]");
            System.exit(2);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            System.err.println("Failure lab could not start: " + exception.getMessage());
            System.exit(1);
        }
    }

    static Duration parseDuration(String[] arguments) {
        if (arguments.length == 0) {
            return Duration.ofSeconds(120);
        }
        if (arguments.length != 2 || !"--duration".equals(arguments[0])) {
            throw new IllegalArgumentException("Expected --duration followed by seconds");
        }
        int seconds;
        try {
            seconds = Integer.parseInt(arguments[1]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Duration must be an integer");
        }
        if (seconds < 10 || seconds > 3_600) {
            throw new IllegalArgumentException("Duration must be between 10 and 3600 seconds");
        }
        return Duration.ofSeconds(seconds);
    }
}
