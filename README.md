# Darkyenus Time Tracker
*for IntelliJ (and other Jetbrains IDEs)*

Simple and lightweight plugin for tracking of time spent on project.

Adds a single status bar widget: click or type to start counting, click again to stop.
Pauses the timer automatically when idle.
Time is saved in IDE's workspace files, does not clutter project's directory.
Can inject time to git commits.

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
