
# project spec: stampstrip

## overview
build a single-file java 17 cli app (jbang style) using picocli that renames files by prefixing each filename with its filesystem creation timestamp.

the app must also detect and remove existing leading date/time prefixes before adding the new prefix.

## project name
stampstrip

## implementation constraints
- single java source file, based on the style of [template.java](template.java).
- java version: 17.
- cli framework: picocli.
- external libraries are allowed if they make the code shorter or clearer (for example apache commons io), but keep dependencies minimal.

## cli behavior

### positional parameters
- accept files to process as positional parameters.
- positional input is an array/list of file paths.

### options
- `--format <pattern>`
	- timestamp output format for the prefix. default. 20250101-1045-filename.md
	- default format should be defined by implementation (must be documented in help).
- `--dry-run`
	- preview mode only.
	- do not rename any file.
- `--filter <regex>`
	- optional regex applied to candidate filenames.
	- only files matching the regex are included for rename.

## rename rules
- timestamp source must be filesystem creation time (not modified time).
- generated timestamp prefix must be lowercase.
- final filename must be lowercase.
- prefix should be added before the cleaned original filename.
- keep file extension.

## existing timestamp stripping
before adding the new timestamp prefix, detect and strip an existing leading timestamp-like prefix from the filename.

the detector must handle:
- date only.
- date + time.
- date + time + milliseconds.
- multiple separator styles between components: dot (`.`), dash (`-`), underscore (`_`), space (` `), or no separator.

examples of patterns that should be treated as removable leading timestamps include:
- `yyyyMMdd`
- `yyyy-MM-dd`
- `yyyy.MM.dd`
- `yyyy_MM_dd`
- `yyyyMMddHHmmss`
- `yyyy-MM-dd_HH-mm-ss`
- `yyyy.MM.dd HH.mm.ss`
- `yyyyMMddHHmmssSSS`

not sure if risky to strip everything before the first letter, assuming it look like a timestamp??  

note: exact matching strategy can be regex-based; it must avoid stripping non-timestamp text when no valid leading timestamp is present.

## user interaction flow
1. collect positional files (leverage as much as possible from picocli parsing, and strong typing, and validations).
2. apply optional regex filter.
3. compute old name -> new name mapping.
4. print all planned renames.
5. ask for confirmation before applying changes.
6. if confirmed and not dry-run, perform renames.
7. print summary of actions.

## confirmation requirements
- always show the list of files that will be renamed.
- ask for explicit confirmation before rename execution.
- if confirmation is declined, exit without changes.
- in dry-run mode, show planned actions and do not rename.

## output expectations
- clear, readable cli output.
- include per-file preview (`from -> to`).
- include final totals (processed, renamed, skipped, failed).

## validation and testing requirements (for implementation phase)
- create sample files in a temporary directory inside this project. and put many tricky files with timestamps to be stripped, and some without.
- include sample names covering:
	- no prefix.
	- existing date prefix.
	- existing date+time prefix.
	- existing date+time+milliseconds prefix.
	- different separators (`.`, `-`, `_`, space, none).
- run the app against these samples.
- verify dry-run, filter, confirmation, and actual rename behavior.

## non-goals
- no recursive directory traversal required unless explicitly added later.
- no gui.