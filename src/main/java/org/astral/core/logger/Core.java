package org.astral.core.logger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jline.reader.LineReader;

import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class Core {

    private static final String RESET = "\u001B[0m";
    private static final String CLEAR_LINE = "\u001B[2K\r";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static volatile LineReader lineReader;
    private static volatile PrintWriter terminalWriter;

    private Core() {
    }

    public static void setLineReader(@Nullable LineReader reader) {
        lineReader = reader;
        terminalWriter = (reader != null) ? reader.getTerminal().writer() : null;
    }

    public static @NotNull LogBuilder atInfo(@NotNull Log context) {
        return new LogBuilder(context, null, Level.INFO);
    }

    public static @NotNull LogBuilder atWarning(@NotNull Log context) {
        return new LogBuilder(context, null, Level.WARN);
    }

    public static @NotNull LogBuilder atError(@NotNull Log context) {
        return new LogBuilder(context, null, Level.ERROR);
    }

    public static @NotNull LogBuilder atInfo(@NotNull Log context, @Nullable String subContext) {
        return new LogBuilder(context, subContext, Level.INFO);
    }

    public static @NotNull LogBuilder atWarning(@NotNull Log context, @Nullable String subContext) {
        return new LogBuilder(context, subContext, Level.WARN);
    }

    public static @NotNull LogBuilder atError(@NotNull Log context, @Nullable String subContext) {
        return new LogBuilder(context, subContext, Level.ERROR);
    }

    public static final class LogBuilder {

        private final Log context;
        private final String subContext;
        private final Level level;

        private LogBuilder(@NotNull Log context, @Nullable String subContext, @NotNull Level level) {
            this.context = context;
            this.subContext = subContext;
            this.level = level;
        }

        public void log(@NotNull String message) {
            String time = LocalTime.now().format(TIME_FORMAT);

            String label = (subContext == null || subContext.isBlank())
                    ? context.getLabel()
                    : context.getLabel() + "/" + subContext;

            String out = '[' + time + "] " +
                    context.getAnsi() + '[' + label + ']' + RESET +
                    " [" + level.getAnsi() + level.name() + RESET + "]: " +
                    message;

            writeLine(out);
        }

        public void update(@NotNull String message) {
            String time = LocalTime.now().format(TIME_FORMAT);

            String out = CLEAR_LINE +
                    '[' + time + "] " +
                    message;

            writeInline(out);
        }

        private void writeLine(String formatted) {
            PrintWriter writer = terminalWriter;
            if (lineReader != null && writer != null) {
                writer.println(formatted);
                writer.flush();
            } else {
                System.out.println(formatted);
            }
        }

        private void writeInline(String formatted) {
            PrintWriter writer = terminalWriter;
            if (lineReader != null && writer != null) {
                writer.print(formatted);
                writer.flush();
            } else {
                System.out.print(formatted);
                System.out.flush();
            }
        }
    }

    private enum Level {
        INFO(ansi(120, 220, 120)),
        WARN(ansi(255, 200, 80)),
        ERROR(ansi(255, 90, 90));

        private final String ansi;

        Level(String ansi) {
            this.ansi = ansi;
        }

        public String getAnsi() {
            return ansi;
        }
    }

    private static @NotNull String ansi(int r, int g, int b) {
        return "\u001B[38;2;" + r + ';' + g + ';' + b + 'm';
    }
}