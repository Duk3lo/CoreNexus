package org.astral.core.logger;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jline.reader.LineReader;

import java.awt.Color;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public final class Core {
    private static final String RESET = "\u001B[0m";
    private static final String CLEAR_LINE = "\u001B[2K\r";

    private static LineReader lineReader;
    private static PrintWriter terminalWriter;

    public static void setLineReader(LineReader reader) {
        lineReader = reader;
        if (reader != null) {
            terminalWriter = reader.getTerminal().writer();
        } else {
            terminalWriter = null;
        }
    }

    public static @NotNull LogBuilder atInfo(Log context) { return new LogBuilder(context, null, "INFO"); }
    public static @NotNull LogBuilder atWarning(Log context) { return new LogBuilder(context, null, "WARN"); }
    public static @NotNull LogBuilder atError(Log context) { return new LogBuilder(context, null, "ERROR"); }

    public static @NotNull LogBuilder atInfo(Log context, String subContext) { return new LogBuilder(context, subContext, "INFO"); }
    public static @NotNull LogBuilder atWarning(Log context, String subContext) { return new LogBuilder(context, subContext, "WARN"); }
    public static @NotNull LogBuilder atError(Log context, String subContext) { return new LogBuilder(context, subContext, "ERROR"); }

    public static class LogBuilder {
        private final Log context;
        private final String subContext;
        private final String level;
        private final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss");

        private LogBuilder(Log context, @Nullable String subContext, String level) {
            this.context = context;
            this.subContext = subContext;
            this.level = level;
        }

        public void log(String message) { print(message, false); }

        public void update(String message) { print(message, true); }

        private void print(String message, boolean isUpdate) {
            if (message == null || message.isBlank()) {
                return;
            }
            String time = LocalTime.now().format(timeFormat);
            String fullLabel = (subContext == null) ? context.label : context.label + ":" + subContext;

            Color levelColor = switch (level) {
                case "ERROR" -> Color.RED;
                case "WARN" -> Color.YELLOW;
                default -> context.color;
            };

            String contextAnsi = toAnsi(context.color);
            String levelAnsi = toAnsi(levelColor);
            String prefix = isUpdate ? CLEAR_LINE : "";
            String cleanMessage = message.stripTrailing();

            String formatted = String.format("%s[%s] %s[%s]%s [%s%s%s]: %s",
                    prefix, time, contextAnsi, fullLabel, RESET,
                    levelAnsi, level, RESET, cleanMessage);

            if (lineReader != null && terminalWriter != null) {
                if (isUpdate) {
                    terminalWriter.print(formatted);
                    terminalWriter.flush();
                } else {
                    lineReader.printAbove(formatted);
                }
            } else {
                if (isUpdate) {
                    System.out.print(formatted);
                    System.out.flush();
                } else {
                    System.out.println(formatted);
                }
            }
        }

        private @NotNull String toAnsi(@NotNull Color color) {
            return String.format("\u001B[38;2;%d;%d;%dm", color.getRed(), color.getGreen(), color.getBlue());
        }
    }
}