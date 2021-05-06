package androidx.test.internal.runner.listener;

import org.junit.internal.TextListener;

import java.io.PrintStream;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * A {@link org.junit.runner.notification.RunListener} for printing a test summary at the end of an
 * instrumentation run.
 */
public class TestSummaryListener extends TextListener {
    public TestSummaryListener(PrintStream writer) {
        super(writer);
    }

    @Override
    protected String elapsedTimeAsString(long runTime) {
        // Use Locale.US so that instrumentation parsers can parse it correctly.
        return NumberFormat.getInstance(Locale.US).format((double) runTime / 1000);
    }
}
