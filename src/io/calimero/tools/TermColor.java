/*
    Calimero 3 - A library for KNX network access
    Copyright (c) 2026, 2026 B. Malinowsky

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/

package io.calimero.tools;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import io.calimero.KNXIllegalArgumentException;
import io.calimero.internal.Executor;
import io.calimero.tools.Theme.ColorPalette;
import io.calimero.tools.Theme.ColorPalette.Style;

class TermColor {
	private static volatile TermColor inst = new TermColor(ColorPalette.None);

	private final ColorPalette palette;

	TermColor(final ColorPalette palette) { this.palette = palette; }


	static boolean useColors() {
		if (System.getenv("NO_COLOR") != null)
			return false;
		final String term = System.getenv("TERM");
		final String wtsession = System.getenv("WT_SESSION");
		return (term != null && !term.equals("dumb") || wtsession != null) && System.console() != null;
	}

	static void init(final Theme theme) { inst = new TermColor(theme.palette()); }

	static TermColor clr() { return inst; }


	String timestamp(final Object value) { return palette.apply(Style.Muted, value); }

	String address(final Object value) { return palette.apply(Style.Address, value); }

	String service(final Object value) { return palette.apply(Style.Service, value); }

	String text(final String value) { return palette.apply(Style.Text, value); }

	String value(final Object value) { return palette.apply(Style.Value, value); }

	String metadata(final String value) { return palette.apply(Style.Muted, value); }

	String bool(final boolean value) { return palette.apply(Style.Action, value); }

	String notice(final String value) { return palette.apply(Style.Notice, value); }

	String action(final String value) { return palette.apply(Style.Action, value); }

	String warn(final Object value) { return palette.apply(Style.Warn, value); }

	String error(final String value) { return palette.apply(Style.Error, value); }
}

enum Theme {
	None, Light, Dark, Auto;

	private static final boolean win = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");

	private static final char Esc = '\033';
	private static final char Bel = '\007';

	private static volatile Theme detected = Theme.None;

	static Theme from(final String color) {
		return switch (color) {
			case "none" -> None;
			case "dark" -> Dark;
			case "light" -> Light;
			case "auto" -> Auto;
			default -> throw new KNXIllegalArgumentException("unknown color theme", color);
		};
	}

	ColorPalette palette() {
		return switch (this) {
			case None -> ColorPalette.None;
			case Light -> ColorPalette.Light;
			case Dark -> ColorPalette.Dark;
			case Auto -> TermColor.useColors() ? detect().palette() : ColorPalette.None;
		};
	}

	@Override
	public String toString() { return name().toLowerCase(Locale.ROOT); }

	record Rgb(int red, int green, int blue) {
		// 24-Bit RGB -> ansi escape sequence
		String ansi() { return Esc + "[38;2;%d;%d;%dm".formatted(red, green, blue); }
	}

	enum ColorPalette {
		Light(new Rgb(0x34, 0x59, 0xa5), new Rgb(0x80, 0x46, 0xa5), new Rgb(0x34, 0x3b, 0x58),
			  new Rgb(0x94, 0x60, 0x00), new Rgb(0x92, 0x99, 0xae),
			  new Rgb(0xd9, 0x77, 0x06), new Rgb(0xc5, 0x1d, 0x1d), new Rgb(0xa4, 0x3e, 0x73), new Rgb(0x08, 0x7e, 0x99)),
		Dark( new Rgb(0x7c, 0xac, 0xf0), new Rgb(0xb8, 0xa1, 0xf1), new Rgb(0xd0, 0xd8, 0xf8),
			  new Rgb(0xa8, 0x87, 0x4f), new Rgb(0x89, 0x92, 0xb5),
			  new Rgb(0xd6, 0xa6, 0x66), new Rgb(0xe5, 0x53, 0x53), new Rgb(0xd5, 0x7a, 0x7a), new Rgb(0x7f, 0xcf, 0xff)),
		None();

		private static final String Reset = Esc + "[0m";

		// keep Style and colors in sync!
		enum Style { Address, Service, Text, Value, Muted, Warn, Error, Action, Notice }
		private final String[] colors;

		ColorPalette() { colors = new String[0]; }

		ColorPalette(final Rgb... colors) {
			this.colors = Arrays.stream(colors).map(Rgb::ansi).toArray(String[]::new);
			if (colors.length != 0 && colors.length != Style.values().length)
				throw new KNXIllegalArgumentException("color palette size must match the number of styles (" + Style.values().length + ")");
		}

		String apply(final Style style, final Object value) {
			return colors.length == 0 ? String.valueOf(value) : colors[style.ordinal()] + value + Reset;
		}
	}

	private static Theme detect() {
		if (win)
			return detected = Theme.Dark;
		if (detected == Theme.None) {
			var theme = Theme.Light;
			try (var __ = new Stty().run("-icanon -echo")) {
				// decide on theme based on perceived luminance of current term bg color
				System.out.print(Esc + "]11;?" + Bel);
				System.out.flush();
				theme = Executor.executor().submit(Theme::readOscResponse).get(100, TimeUnit.MILLISECONDS)
						.map(rgb -> {
							final double luma = (0.2126 * rgb.red() + 0.7152 * rgb.green() + 0.0722 * rgb.blue()) / 255.0;
							return luma > 0.5 ? Theme.Light : Theme.Dark;
						}).orElse(theme);
			}
			catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			catch (IOException | ExecutionException | TimeoutException | RuntimeException e) {
				Main.out().log(System.Logger.Level.DEBUG,
						"could not detect terminal background color ({0}), using theme ''{1}''", e.getMessage(), theme);
			}
			finally {
				detected = theme;
			}
		}
		return detected;
	}

	private static Optional<Rgb> readOscResponse() throws IOException {
		final var rgbPattern = Pattern.compile("rgb:(\\p{XDigit}+)/(\\p{XDigit}+)/(\\p{XDigit}+)");
		final var res = new StringBuilder();
		for (int c = System.in.read(); c != -1 && !Thread.currentThread().isInterrupted(); c = System.in.read()) {
			res.append((char) c);
			final int len = res.length();
			if (c == Bel || c == '\\' && len >= 2 && res.charAt(len - 2) == Esc) {
				final var matcher = rgbPattern.matcher(res);
				if (matcher.find())
					return Optional.of(new Rgb(comp(matcher.group(1)), comp(matcher.group(2)), comp(matcher.group(3))));
				break;
			}
		}
		return Optional.empty();
	}

	private static int comp(final String hex) {
		final int max = (1 << (hex.length() * 4)) - 1;
		return (Integer.parseInt(hex, 16) * 255 + max / 2) / max;
	}
}

// linux/mac only
final class Stty implements AutoCloseable {
	private final String savedSettings;

	Stty() throws IOException, InterruptedException { savedSettings = stty("-g < /dev/tty"); }

	Stty run(final String args) throws IOException, InterruptedException { stty(args + " < /dev/tty"); return this; }

	@Override
	public void close() throws IOException, InterruptedException { stty(savedSettings + " < /dev/tty"); }

	private static String stty(final String args) throws IOException, InterruptedException {
		final var process = new ProcessBuilder("sh", "-c", "stty " + args)
				.redirectInput(ProcessBuilder.Redirect.INHERIT)
				.redirectOutput(ProcessBuilder.Redirect.PIPE)
				.redirectError(ProcessBuilder.Redirect.INHERIT)
				.start();

		final String output = new String(process.getInputStream().readAllBytes()).trim();
		if (process.waitFor() != 0)
			throw new IOException("stty " + args + " failed (exit value " + process.exitValue() + ")");
		return output;
	}
}
