package com.darkyen;

import java.util.*;
import java.util.logging.Logger;

import static com.darkyen.Util.msToS;


/**
 *
 */
public final class TimePattern {

	private static final Logger LOG = Logger.getLogger(TimePattern.class.getName());

	public final String source;
	private final List<Token> tokens;
	private final EnumSet<TimeUnit> units;

	private TimePattern(String source, List<Token> tokens) {
		this.source = source;
		this.tokens = tokens;

		final EnumSet<TimeUnit> units = EnumSet.noneOf(TimeUnit.class);
		for (Token token : tokens) {
			if (token instanceof TimeToken) {
				units.add(((TimeToken) token).unit);
			}
		}
		this.units = units;
	}

	public static final class ParseError {
		public final int index;
		public final String message;

		ParseError(int index, String message) {
			this.index = index;
			this.message = message;
		}
	}

	public String millisecondsToString(long ms) {
		return secondsToString((int) msToS(ms));
	}

	public String secondsToString(int timeSeconds) {
		EnumMap<TimeUnit, Integer> time = secondsToUnits(timeSeconds, units);

		{
			// When some units are omitted because they are trailing,
			// adjust the unit-value distribution
			final EnumSet<TimeUnit> participatingUnits = EnumSet.noneOf(TimeUnit.class);
			for (Token token : tokens) {
				if (token instanceof TimeToken) {
					if (((TimeToken) token).willParticipate(time)) {
						participatingUnits.add(((TimeToken) token).unit);
					}
				}
			}

			if (participatingUnits.size() < units.size()) {
				time = secondsToUnits(timeSeconds, participatingUnits);
			}
		}

		final StringBuilder sb = new StringBuilder();
		for (Token token : tokens) {
			token.append(sb, time);
		}

		// Remove leading, trailing and duplicate spaces
		int writer = 0;
		int reader = 0;
		// Leading spaces
		while (reader < sb.length() && sb.charAt(reader) == ' ') {
			reader++;
		}
		trimming:
		while (reader < sb.length()) {
			// Normal characters
			do {
				sb.setCharAt(writer++, sb.charAt(reader++));
				if (reader >= sb.length()) {
					break trimming;
				}
			} while (sb.charAt(reader) != ' ');
			// Spaces
			do {
				reader++;
				if (reader >= sb.length()) {
					break trimming;
				}
			} while (sb.charAt(reader) == ' ');
			sb.setCharAt(writer++, ' ');
		}
		sb.setLength(writer);

		return sb.toString();
	}

	public static TimePattern parse(CharSequence pattern) {
		final ArrayList<ParseError> errors = new ArrayList<>();
		final TimePattern result = parse(pattern, errors);

		if (!errors.isEmpty()) {
			LOG.warning("Pattern '"+pattern+"' had "+errors.size()+" ignored error(s)");
		}

		return result;
	}

	public static TimePattern parse(CharSequence pattern, List<ParseError> parseErrors) {
		final ArrayList<Token> tokens = new ArrayList<>();

		final StringBuilder literalBuffer = new StringBuilder();
		for (int[] i = {0}; i[0] < pattern.length(); ) {
			final int originalI = i[0];
			final TimeToken timeToken = parseTimeToken(pattern, i, parseErrors);
			if (timeToken != null) {
				if (literalBuffer.length() > 0) {
					tokens.add(new LiteralToken(literalBuffer.toString()));
					literalBuffer.setLength(0);
				}

				tokens.add(timeToken);
			} else {
				literalBuffer.append(pattern.charAt(originalI));
				i[0] = originalI + 1;
			}
		}

		if (literalBuffer.length() > 0) {
			tokens.add(new LiteralToken(literalBuffer.toString()));
			literalBuffer.setLength(0);
		}

		return new TimePattern(pattern.toString(), tokens);
	}

	private static boolean parse(CharSequence source, int[] positionRef, String required) {
		if (positionRef[0] + required.length() > source.length()) {
			return false;
		}

		for (int si = positionRef[0], ri = 0; ri < required.length(); si++, ri++) {
			if (source.charAt(si) != required.charAt(ri)) {
				return false;
			}
		}

		positionRef[0] += required.length();
		return true;
	}

	/**
	 * Parse time token at positionRef[0] and move positionRef[0] to the first character after the pattern.
	 * @return parsed time token or null (in which case positionRef[0] may contain bogus)
	 */
	private static TimeToken parseTimeToken(CharSequence source, int[] positionRef, List<ParseError> parseErrors) {
		/*
		Format in pseudo regex:
		[lt0]?<u>' '?('"'[^"]+'"')?'s'?

    	1. Pattern itself may be prefixed with one of specified characters.
    		This signifies which mode is used.
    		If this leads to the format not being printed and the {{pattern}} is on each side surrounded by space,
    		one space is then dropped, to prevent unsightly double-spaces (start or end of string is treated as space too).
        	- 'l' OMIT_LEADING
        	- 't' OMIT_TRAILING
        	- '0' OMIT_ZERO
        2. TimeUnit - letter which specifies which unit this pattern is about
    	3. Then an optional space character, which, if present, denotes that there should be a space between the number and unit string
    	4. Then, optionally, arbitrary string enclosed in " quotes (the string may not contain ").
    		This string will be used instead of the default unit (which is the unit-letter itself).
    	5. Then, optionally, single letter 's' to denote that the unit string should have s appended, if the value is not 1.

    	Whole time token specifier X is enclosed in {{X}}.
		 */
		if (!parse(source, positionRef, "{{")) {
			return null;
		}

		TimeTokenMode mode = TimeTokenMode.ALWAYS;
		if (parse(source, positionRef, "l")) {
			mode = TimeTokenMode.OMIT_LEADING;
		} else if (parse(source, positionRef, "t")) {
			mode = TimeTokenMode.OMIT_TRAILING;
		} else if (parse(source, positionRef, "0")) {
			mode = TimeTokenMode.OMIT_ZERO;
		}

		TimeUnit unit = null;
		for (TimeUnit u : TimeUnit.UNITS) {
			if (parse(source, positionRef, u.unit)) {
				unit = u;
				break;
			}
		}

		if (unit == null) {
			// No unit specified
			if (mode != TimeTokenMode.ALWAYS) {
				parseErrors.add(new ParseError(positionRef[0], "Time unit character expected (one of 'w', 'd', 'h', 'm' or 's')"));
			} else {
				parseErrors.add(new ParseError(positionRef[0], "Time unit or mode character expected (time unit is one of 'w', 'd', 'h', 'm' or 's' and mode is one of 'l', 't' or '0')"));
			}
			return null;
		}

		final boolean spaceBeforeUnit = parse(source, positionRef, " ");

		final String unitText;
		final int unitTextStartIndex = positionRef[0];
		if (parse(source, positionRef, "\"")) {
			final StringBuilder unitTextSb = new StringBuilder();
			parsingUnitText:
			{
				while (positionRef[0] < source.length()) {
					final char c = source.charAt(positionRef[0]++);
					if (c == '"') {
						break parsingUnitText;
					} else {
						unitTextSb.append(c);
					}
				}
				// String is unclosed!
				parseErrors.add(new ParseError(unitTextStartIndex, "Unit description text string is not closed"));
				return null;
			}
			unitText = unitTextSb.toString();
		} else {
			unitText = unit.unit;
		}

		final boolean pluralize = parse(source, positionRef, "s");

		if (!parse(source, positionRef, "}}")) {
			parseErrors.add(new ParseError(positionRef[0], "Closing '}}' expected"));
			return null;
		}

		return new TimeToken(unit, mode, unitText, spaceBeforeUnit, pluralize ? "s" : null);
	}

	private interface Token {
		void append(StringBuilder sb, EnumMap<TimeUnit, Integer> time);
	}

	private static final class LiteralToken implements Token {

		private final String value;

		LiteralToken(String value) {
			this.value = value;
		}

		@Override
		public void append(StringBuilder sb, EnumMap<TimeUnit, Integer> time) {
			sb.append(value);
		}
	}

	private static final class TimeToken implements Token {

		private final TimeUnit unit;
		private final TimeTokenMode mode;
		private final String text;
		private final String pluralizeWith;
		private final boolean unitPrefixedWithSpace;

		TimeToken(TimeUnit unit, TimeTokenMode mode, String text, boolean unitPrefixedWithSpace, String pluralizeWith) {
			this.unit = unit;
			this.mode = mode;
			this.text = text;
			this.unitPrefixedWithSpace = unitPrefixedWithSpace;
			this.pluralizeWith = pluralizeWith;
		}

		public boolean willParticipate(EnumMap<TimeUnit, Integer> time) {
			if (mode == TimeTokenMode.OMIT_TRAILING) {
				for (TimeUnit timeUnit : unit.largerUnits()) {
					if (time.getOrDefault(timeUnit, 0) != 0) {
						// Omit: One of larger units is not zero, so this is unnecessary detail
						return false;
					}
				}
			}

			return true;
		}

		private boolean willRender(EnumMap<TimeUnit, Integer> time) {
			final Integer timeValue = time.get(unit);
			if (timeValue == null) {
				// Previously omitted value
				return false;
			}

			switch (mode) {
				case ALWAYS:
				default:
					// Always present
					return true;
				case OMIT_LEADING:
					if (timeValue != 0) {
						return true;
					}
					for (TimeUnit timeUnit : unit.largerUnits()) {
						if (time.getOrDefault(timeUnit, 0) != 0) {
							return true;
						}
					}
					// Omit: It is zero and all larger units are also zero
					return false;
				case OMIT_TRAILING:
					for (TimeUnit timeUnit : unit.largerUnits()) {
						if (time.getOrDefault(timeUnit, 0) != 0) {
							// Omit: One of larger units is not zero, so this is unnecessary detail
							return false;
						}
					}
					return true;
				case OMIT_ZERO:
					return timeValue != 0;
			}
		}

		@Override
		public void append(StringBuilder sb, EnumMap<TimeUnit, Integer> time) {
			if (!willRender(time)) {
				return;
			}

			final int timeValue = time.get(unit);

			// This value should get appended
			sb.append(timeValue);

			if (unitPrefixedWithSpace) {
				sb.append(' ');
			}

			sb.append(text);
			if (pluralizeWith != null && timeValue != 1) {
				sb.append(pluralizeWith);
			}
		}
	}

	private enum TimeTokenMode {
		/** Always present */
		ALWAYS,
		/** Present only if not zero or any of present larger units is not zero (to hide leading zeroes) */
		OMIT_LEADING,
		/** Present only if all larger present units are zero (to hide unnecessary detail) */
		OMIT_TRAILING,
		/** Present only if not zero */
		OMIT_ZERO
	}

	private enum TimeUnit {
		WEEK("w", 60 * 60 * 24 * 7),// 7 days
		DAY("d", 60 * 60 * 24),// 24 hours
		HOUR("h", 60 * 60),// 60 minutes
		MINUTE("m", 60),// 60 seconds
		SECOND("s", 1);// 1 second

		final String unit;
		final int ofSeconds;

		private TimeUnit[] largerUnits = null;

		TimeUnit(String unit, int ofSeconds) {
			this.unit = unit;
			this.ofSeconds = ofSeconds;
		}

		int of(TimeUnit smallerUnit) {
			return ofSeconds / smallerUnit.ofSeconds;
		}

		TimeUnit[] largerUnits() {
			TimeUnit[] largerUnits = this.largerUnits;
			if (largerUnits == null) {
				largerUnits = this.largerUnits = Arrays.copyOf(UNITS, ordinal());
			}
			return largerUnits;
		}

		private static final TimeUnit[] UNITS = values();

	}

	/**
	 * Split the duration in seconds to bins according to selected units, so that can be used to represent
	 * the time naturally.
	 *
	 * When the amount cannot be represented precisely (only when SECOND is not in selectedUnits),
	 * the value of the smallest unit is rounded so that it is closer to the real value.
	 *
	 * <pre>
	 *     Examples:
	 *     123 seconds in (WEEK, DAY, HOUR, MINUTE, SECOND) -> {WEEK: 0, DAY: 0, HOUR: 0, MINUTE: 2, SECOND: 3}
	 *     123 seconds in (MINUTE) -> {MINUTE: 2}
	 *     100 seconds in (MINUTE) -> {MINUTE: 2} because of rounding
	 *     59 minutes and 59 seconds in (HOUR, MINUTE) -> {HOUR: 1, MINUTE: 0} because of rounding
	 * </pre>
	 */
	private static EnumMap<TimeUnit, Integer> secondsToUnits(int seconds, EnumSet<TimeUnit> selectedUnits) {
		final EnumMap<TimeUnit, Integer> result = new EnumMap<>(TimeUnit.class);
		TimeUnit lastUnit = null;
		for (TimeUnit unit : selectedUnits) {
			result.put(unit, seconds / unit.ofSeconds);
			seconds %= unit.ofSeconds;

			lastUnit = unit;
		}

		if (seconds > 0 && lastUnit != null && seconds * 2 > lastUnit.ofSeconds) {
			result.put(lastUnit, result.get(lastUnit) + 1);

			redistributePossiblyOvergrownUnit(result, lastUnit);
		}

		return result;
	}

	private static void redistributePossiblyOvergrownUnit(EnumMap<TimeUnit, Integer> map, TimeUnit modifiedUnit) {
		final int newValue = map.get(modifiedUnit);

		final TimeUnit[] largerUnits = modifiedUnit.largerUnits();
		for (int i = largerUnits.length - 1; i >= 0; i--) {
			final TimeUnit largerUnit = largerUnits[i];
			if (!map.containsKey(largerUnit)) {
				continue;
			}

			final int oneLargeIsThisManyModified = largerUnit.of(modifiedUnit);
			if (oneLargeIsThisManyModified <= newValue) {
				map.put(modifiedUnit, newValue - oneLargeIsThisManyModified);
				map.put(largerUnit, map.get(largerUnit) + 1);
				redistributePossiblyOvergrownUnit(map, largerUnit);
			}

			break;
		}
	}
}
