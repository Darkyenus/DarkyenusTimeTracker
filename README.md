# Darkyen's Time Tracker
*for IntelliJ (and other Jetbrains IDEs)*

Track the time spent on a project through a lightweight status bar widget.

## Overview
- Click the widget or start typing to start counting
- Pauses the timer automatically when idle
- Click timer widget for options
- *Git* integration, inject the time it took to create the commit into the commit message

## Features
- Pause or stop after a given time of inactivity (clicking any buttons/keys or scrolling).
- When returning after a period of inactivity and the counting is paused, short time durations are automatically counted in, long periods can be counted in manually through a message popup. The auto-count duration is configurable.
- Start counting automatically when you type something, even when stopped. Configurable.
- Pause the timer when you work on a different project within the same IDE process, useful when switching projects frequently. Configurable.
- The time format on the widget is configurable using a simple but flexible template substitution. Includes clear documentation of the format, including examples of frequently requested formats.
- Ability to inject the time it took to create a *Git* commit through a *Git commit hook*, using the same customizable time format. The time is appended at the end of the message, unless your template contains `<#DTT#>`, which is then replaced by the formatted time instead.
- Reset the time (and the hidden Git counter time) manually through a button in settings or through an [IDE action](https://www.jetbrains.com/help/idea/customize-actions-menus-and-toolbars.html).
- Manually adjust counted time.

### Internals
The time in IDE is stored in IDE's workspace XML, typically at `.idea/workspace.xml`. The section looks roughly like this:
```xml
<component name="DarkyenusTimeTracker">
    <option name="totalTimeSeconds" value="162561" />
    <option name="gitIntegration" value="true" />
</component>
```
The exact format is controlled by the IDE and may change in the future.

Git integration files are:
- Commit hook at `.git/hooks/prepare-commit-msg` (a POSIX shell script with `/bin/sh` shebang)
- Git time tracking file at `.git/.darkyenus_time_tracker_commit_time` (plain text file)
- `.git/.darkyenus_time_tracker_commit_time.zero` may be created temporarily during commit hook run, but shouldn't persist
    - If you find it, you can safely delete it

### Installing
Download from [plugin repository](https://plugins.jetbrains.com/plugin/9286) or from [releases](https://github.com/Darkyenus/DarkyenusTimeTracker/releases).

### Building from source
There are no dependencies, this is a pure IntelliJ plugin. PR's are welcome!
