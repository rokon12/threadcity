# ThreadCity Collector

Build and capture a short JVM incident window:

```bash
mvn clean package
java -jar target/threadcity-collector-0.1.0-SNAPSHOT.jar \
  --pid 12345 --duration 20 --snapshots 3 --output incident.threadcity
```

The collector uses the current JDK's `jcmd` binary. It creates a ZIP-compatible `.threadcity` bundle containing chronological `Thread.print -l` snapshots, a profile JFR recording, and capture metadata. It refuses to overwrite an existing output file.
