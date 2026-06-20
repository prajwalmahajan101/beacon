package internal.beacon.conformance;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.List;
import java.util.stream.Collectors;

/**
 * JUnit 5 Extension: after each test, asserts no Thread whose name starts with
 * {@code "beacon-"} is still alive. Catches a leaked {@code BatchFlusher} (or any
 * other Beacon daemon) that a test forgot to close via {@code sdk.close()}.
 *
 * <p>Registered via {@code @ExtendWith(BeaconLeakGuard.class)} on the test class.
 * Lives next to {@code ConformanceTest.java} in
 * {@code beacon-s0-contract/conformance/java/} because {@code :conformance-java}
 * only sees {@code :beacon-sdk-java}'s production JAR via {@code testImplementation}
 * — test-source classes do not cross subproject boundaries.
 *
 * <p>See M1.6 success criterion #5 and {@code .journal/M1.6.md}.
 */
public final class BeaconLeakGuard implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext ctx) {
        // Brief grace period for an in-flight close() to complete.
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        List<String> leaked = Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(n -> n.startsWith("beacon-"))
                .sorted()
                .collect(Collectors.toList());

        if (!leaked.isEmpty()) {
            throw new AssertionError(
                    "BeaconLeakGuard: leaked daemon threads after test '"
                            + ctx.getDisplayName() + "': " + leaked
                            + ". Did you forget sdk.close() in finally?");
        }
    }
}
