# Stampstrip

Stampstrip is a single-file Java 17 CLI (Picocli + JBang) that renames files by prefixing each filename with its filesystem creation timestamp.

It also strips existing leading timestamp-like prefixes before applying the new one.

## Requirements

- Java 17+
- JBang

## Project Setup

JBang wrapper files are included in this repository:

- `jbang`
- `jbang.cmd`
- `jbang.ps1`

This allows running commands without requiring a globally installed `jbang` executable on every environment.

## Usage

### Windows (CMD)

```bat
jbang.cmd stampstrip.java [options] <file1> <file2> ...
```

### Windows (PowerShell)

```powershell
.\jbang.ps1 stampstrip.java [options] <file1> <file2> ...
```

### macOS/Linux

```bash
./jbang stampstrip.java [options] <file1> <file2> ...
```

## Options

- `--format <pattern>`: output timestamp format (default: `yyyyMMdd-HHmm`)
- `--dry-run`: preview only, no file changes
- `--filter <regex>`: process only files whose names match the regex
- `-h`, `--help`: show help
- `-V`, `--version`: show version

## Output Format

Default final filename shape:

```text
20250101-1045-filename.md
```

Rules:

- timestamp is always a leading prefix
- prefix uses file creation time (not modified time)
- resulting filename is lowercased
- file extension is preserved

## Examples

Preview changes:

```powershell
.\jbang.ps1 stampstrip.java --dry-run .\notes.md .\report.pdf
```

Run with confirmation:

```powershell
.\jbang.ps1 stampstrip.java .\notes.md .\report.pdf
```

Use custom timestamp format:

```powershell
.\jbang.ps1 stampstrip.java --format yyyyMMdd-HHmmss .\notes.md
```
