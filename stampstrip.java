///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.7.6

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Command(
        name = "stampstrip",
        mixinStandardHelpOptions = true,
        version = "stampstrip 0.1",
        description = "Prefixes files with creation timestamp after stripping existing leading timestamp-like prefixes.",
        footer = "Default --format is yyyyMMdd-HHmm, resulting in names like 20250101-1045-filename.md"
)
class stampstrip implements Callable<Integer> {

    static final String DEFAULT_FORMAT = "yyyyMMdd-HHmm";

    @Parameters(arity = "1..*", paramLabel = "FILE", description = "Files to process")
    private List<Path> files;

    @Option(names = "--format", description = "Timestamp format used in prefix (default: " + DEFAULT_FORMAT + ")")
    private String format = DEFAULT_FORMAT;

    @Option(names = "--dry-run", description = "Preview only; do not rename files")
    private boolean dryRun;

    @Option(names = "--filter", description = "Regex filter applied to original file names")
    private String filterRegex;

    public static void main(String... args) {
        int exitCode = new CommandLine(new stampstrip()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        StampstripConfig config = StampstripConfig.from(format, dryRun, filterRegex);
        return new StampstripApp(new ConsolePrompter()).run(config, files);
    }
}

final class StampstripApp {
    private final Prompter prompter;

    StampstripApp(Prompter prompter) {
        this.prompter = prompter;
    }

    int run(StampstripConfig config, List<Path> inputs) {
        Summary summary = new Summary(inputs.size());
        List<RenamePlan> plans = buildPlans(config, inputs, summary);
        printPlanned(plans);

        if (config.dryRun()) {
            summary.addSkipped(plans.size());
            summary.print(plans.size());
            return summary.exitCode();
        }

        if (!plans.isEmpty() && !prompter.confirm()) {
            System.out.println("Confirmation declined. No changes were made.");
            summary.addSkipped(plans.size());
            summary.print(plans.size());
            return 0;
        }

        executePlans(plans, summary);
        summary.print(plans.size());
        return summary.exitCode();
    }

    private List<RenamePlan> buildPlans(StampstripConfig config, List<Path> inputs, Summary summary) {
        List<RenamePlan> plans = new ArrayList<>();
        for (Path input : inputs) {
            planForInput(input, config, summary).ifPresent(plans::add);
        }
        return detectBatchCollisions(plans, summary);
    }

    private java.util.Optional<RenamePlan> planForInput(Path input, StampstripConfig cfg, Summary summary) {
        Path file = input.toAbsolutePath().normalize();
        String originalName = file.getFileName().toString();

        if (!FileChecks.isRegularFile(file)) {
            summary.addFailed();
            System.out.printf("SKIP (not a regular file): %s%n", file);
            return java.util.Optional.empty();
        }
        if (!cfg.matchesFilter(originalName)) {
            summary.addSkipped(1);
            System.out.printf("SKIP (filter): %s%n", file);
            return java.util.Optional.empty();
        }

        String newName = NewNameBuilder.build(file, originalName, cfg, summary);
        if (newName == null) {
            return java.util.Optional.empty();
        }
        Path target = file.resolveSibling(newName);
        if (target.equals(file)) {
            summary.addSkipped(1);
            System.out.printf("SKIP (already matches target): %s%n", file);
            return java.util.Optional.empty();
        }
        if (Files.exists(target)) {
            summary.addFailed();
            System.out.printf("FAIL (target already exists): %s -> %s%n", file.getFileName(), target.getFileName());
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new RenamePlan(file, target));
    }

    private List<RenamePlan> detectBatchCollisions(List<RenamePlan> plans, Summary summary) {
        Map<Path, List<RenamePlan>> grouped = new LinkedHashMap<>();
        for (RenamePlan plan : plans) {
            grouped.computeIfAbsent(plan.to(), ignored -> new ArrayList<>()).add(plan);
        }

        List<RenamePlan> filtered = new ArrayList<>();
        for (List<RenamePlan> sameTarget : grouped.values()) {
            if (sameTarget.size() == 1) {
                filtered.add(sameTarget.get(0));
                continue;
            }
            for (RenamePlan plan : sameTarget) {
                summary.addFailed();
                System.out.printf("FAIL (target collision in batch): %s -> %s%n",
                        plan.from().getFileName(), plan.to().getFileName());
            }
        }
        return filtered;
    }

    private void printPlanned(List<RenamePlan> plans) {
        System.out.println();
        System.out.println("Planned renames:");
        if (plans.isEmpty()) {
            System.out.println("  (none)");
            return;
        }
        for (RenamePlan plan : plans) {
            System.out.printf("  %s -> %s%n", plan.from().getFileName(), plan.to().getFileName());
        }
    }

    private void executePlans(List<RenamePlan> plans, Summary summary) {
        for (RenamePlan plan : plans) {
            if (RenameExecutor.move(plan)) {
                summary.addRenamed();
                continue;
            }
            summary.addFailed();
            System.out.printf("FAIL (rename): %s -> %s%n", plan.from().getFileName(), plan.to().getFileName());
        }
    }
}

record StampstripConfig(DateTimeFormatter formatter, boolean dryRun, Pattern filterPattern) {
    static StampstripConfig from(String format, boolean dryRun, String filterRegex) {
        DateTimeFormatter formatter = Validators.formatter(format);
        Pattern filter = Validators.filterPattern(filterRegex);
        return new StampstripConfig(formatter, dryRun, filter);
    }

    boolean matchesFilter(String fileName) {
        return filterPattern == null || filterPattern.matcher(fileName).find();
    }
}

final class Validators {
    private Validators() {
    }

    static DateTimeFormatter formatter(String pattern) {
        try {
            return DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException ex) {
            throw new CommandLine.ParameterException(new CommandLine(new stampstrip()),
                    "Invalid --format pattern: " + ex.getMessage(), ex);
        }
    }

    static Pattern filterPattern(String regex) {
        if (regex == null || regex.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(regex);
        } catch (Exception ex) {
            throw new CommandLine.ParameterException(new CommandLine(new stampstrip()),
                    "Invalid --filter regex: " + ex.getMessage(), ex);
        }
    }
}

final class NewNameBuilder {
    private NewNameBuilder() {
    }

    static String build(Path file, String originalName, StampstripConfig cfg, Summary summary) {
        NameParts parts = NameParts.from(originalName);
        LocalDateTime created = FileChecks.creationTime(file);
        if (created == null) {
            summary.addFailed();
            System.out.printf("FAIL (creation time unreadable): %s%n", file);
            return null;
        }

        String prefix = cfg.formatter().format(created).toLowerCase(Locale.ROOT);
        String cleanBase = TimestampPrefixStripper.strip(parts.base(), prefix);
        String base = cleanBase.isBlank() ? parts.base() : cleanBase;
        String normalizedBase = FileNameNormalizer.normalize(base);
        return (prefix + "-" + normalizedBase + parts.extension()).toLowerCase(Locale.ROOT);
    }
}

final class FileChecks {
    private FileChecks() {
    }

    static boolean isRegularFile(Path file) {
        return Files.exists(file) && Files.isRegularFile(file);
    }

    static LocalDateTime creationTime(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return attrs.creationTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (IOException ex) {
            return null;
        }
    }
}

final class TimestampPrefixStripper {
    private static final Pattern PREFIX_PATTERN = Pattern.compile(
            "^(?<year>\\d{4})(?<s1>[._\\- ]?)(?<month>\\d{2})(?<s2>[._\\- ]?)(?<day>\\d{2})"
                    + "(?:(?<s3>[._\\- ]?)(?<hour>\\d{2})(?<s4>[._\\- ]?)(?<minute>\\d{2})"
                    + "(?:(?<s5>[._\\- ]?)(?<second>\\d{2})(?:(?<s6>[._\\- ]?)(?<ms>\\d{3}))?)?)?"
                    + "(?<post>[._\\- ]*)(?<rest>.*)$"
    );

    private TimestampPrefixStripper() {
    }

    static String strip(String baseName, String currentPrefix) {
        String withoutCurrentPrefix = stripCurrentPrefix(baseName, currentPrefix);
        return stripOneTimestamp(withoutCurrentPrefix);
    }

    private static String stripCurrentPrefix(String baseName, String currentPrefix) {
        if (currentPrefix == null || currentPrefix.isBlank()) {
            return baseName;
        }

        String marker = currentPrefix + "-";
        String candidate = baseName;
        while (candidate.toLowerCase(Locale.ROOT).startsWith(marker)) {
            candidate = candidate.substring(marker.length());
        }
        return candidate;
    }

    private static String stripOneTimestamp(String baseName) {
        Matcher matcher = PREFIX_PATTERN.matcher(baseName);
        if (!matcher.matches() || !validDateTime(matcher)) {
            return baseName;
        }

        String post = matcher.group("post");
        String rest = matcher.group("rest");
        if (!rest.isEmpty() && (post == null || post.isEmpty())) {
            return baseName;
        }
        return rest == null || rest.isBlank() ? "" : rest;
    }

    private static boolean validDateTime(Matcher m) {
        try {
            int year = Integer.parseInt(m.group("year"));
            int month = Integer.parseInt(m.group("month"));
            int day = Integer.parseInt(m.group("day"));
            LocalDate.of(year, month, day);

            String hourText = m.group("hour");
            if (hourText == null) {
                return true;
            }

            int hour = Integer.parseInt(hourText);
            int minute = Integer.parseInt(m.group("minute"));
            String secondText = m.group("second");

            if (secondText == null) {
                LocalTime.of(hour, minute);
                return true;
            }

            int second = Integer.parseInt(secondText);
            String msText = m.group("ms");
            if (msText == null) {
                LocalTime.of(hour, minute, second);
                return true;
            }

            int ms = Integer.parseInt(msText);
            LocalTime.of(hour, minute, second, ms * 1_000_000);
            return true;
        } catch (DateTimeException | IllegalArgumentException ex) {
            return false;
        }
    }
}

final class FileNameNormalizer {
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    private FileNameNormalizer() {
    }

    static String normalize(String fileBaseName) {
        return WHITESPACE_PATTERN.matcher(fileBaseName).replaceAll("-");
    }
}

final class RenameExecutor {
    private RenameExecutor() {
    }

    static boolean move(RenamePlan plan) {
        try {
            Files.move(plan.from(), plan.to(), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException ignored) {
            return fallbackMove(plan);
        }
    }

    private static boolean fallbackMove(RenamePlan plan) {
        try {
            Files.move(plan.from(), plan.to());
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}

record NameParts(String base, String extension) {
    static NameParts from(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            return new NameParts(fileName.substring(0, dot), fileName.substring(dot));
        }
        return new NameParts(fileName, "");
    }
}

record RenamePlan(Path from, Path to) {
}

interface Prompter {
    boolean confirm();
}

final class ConsolePrompter implements Prompter {
    @Override
    public boolean confirm() {
        String answer = readAnswer();
        if (answer == null) {
            return false;
        }
        String normalized = answer.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("y") || normalized.equals("yes");
    }

    private String readAnswer() {
        Console console = System.console();
        if (console != null) {
            return console.readLine("Apply these renames? [y/N]: ");
        }
        System.out.print("Apply these renames? [y/N]: ");
        Scanner scanner = new Scanner(System.in);
        return scanner.hasNextLine() ? scanner.nextLine() : "";
    }
}

final class Summary {
    private final int processed;
    private int renamed;
    private int skipped;
    private int failed;

    Summary(int processed) {
        this.processed = processed;
    }

    void addRenamed() {
        renamed++;
    }

    void addSkipped(int count) {
        skipped += count;
    }

    void addFailed() {
        failed++;
    }

    void print(int planned) {
        System.out.println();
        System.out.println("Summary:");
        System.out.printf("  processed: %d%n", processed);
        System.out.printf("  planned:   %d%n", planned);
        System.out.printf("  renamed:   %d%n", renamed);
        System.out.printf("  skipped:   %d%n", skipped);
        System.out.printf("  failed:    %d%n", failed);
    }

    int exitCode() {
        return failed > 0 ? 1 : 0;
    }
}