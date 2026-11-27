package br.com.scriptping.reports;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;

final class ReportPdf {
    enum Alignment { LEFT, CENTER, RIGHT }
    enum Orientation { PORTRAIT, LANDSCAPE }

    record Column(String label, int percent, Alignment alignment) {}
    record Indicator(String label, String value) {}
    record Definition(String id, String title, String subtitle, Orientation orientation,
                      List<Indicator> indicators, String section, List<Column> columns,
                      List<List<String>> rows, String emptyMessage, String total) {}

    record Document(String filename, byte[] content) {}

    record Issuance(Instant instant, ZoneId zone, String user) {
        String filename(String id) {
            return id + "_" + DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(zone).format(instant) + ".pdf";
        }
    }

    private static final Charset ENCODING = Charset.forName("windows-1252");
    private static final float MARGIN = 15 * 72f / 25.4f;
    private static final float LINE_HEIGHT = 11;
    private static final float TABLE_BOTTOM = MARGIN + 20;
    private static final String INK = "0.09 0.15 0.21";
    private static final String MUTED = "0.29 0.34 0.41";
    private static final String RULE = "0.63 0.68 0.73";
    // Standard Helvetica advance widths in PDF font units, from ASCII space through tilde.
    private static final int[] WIDTHS = {
        278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,
        556,556,556,556,556,556,556,556,556,556,278,278,584,584,584,556,
        1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,
        667,778,722,667,611,722,667,944,667,667,611,278,278,278,469,556,
        333,556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,
        556,556,333,500,278,556,500,722,500,500,500,334,260,334,584
    };

    private final Definition definition;
    private final Issuance issuance;
    private final float width;
    private final float height;
    private final float usable;
    private final List<StringBuilder> pages = new ArrayList<>();
    private StringBuilder page;
    private float y;

    private ReportPdf(Definition definition, Issuance issuance) {
        this.definition = definition;
        this.issuance = issuance;
        boolean portrait = definition.orientation() == Orientation.PORTRAIT;
        width = portrait ? 595.28f : 841.89f;
        height = portrait ? 841.89f : 595.28f;
        usable = width - 2 * MARGIN;
    }

    static String date(Instant instant, ZoneId zone) {
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(zone).format(instant);
    }

    static String number(long value) {
        return String.format(Locale.forLanguageTag("pt-BR"), "%,d", value);
    }

    static Document render(Definition definition, Issuance issuance) {
        var pdf = new ReportPdf(definition, issuance);
        pdf.layout();
        return new Document(issuance.filename(definition.id()), pdf.serialize());
    }

    private void layout() {
        newPage();
        boolean pageHasRows = false;
        for (var row : definition.rows()) {
            var cells = new ArrayList<List<String>>();
            for (int i = 0; i < row.size(); i++) {
                cells.add(wrap(row.get(i), columnWidth(definition.columns().get(i)) - 12, 9));
            }
            int lines = cells.stream().mapToInt(List::size).max().orElse(1);
            if (pageHasRows && y - lines * LINE_HEIGHT < TABLE_BOTTOM + 16) newPage();
            // Keep even an exceptionally long row together and reserve space for the total.
            float leading = Math.min(LINE_HEIGHT, (y - TABLE_BOTTOM - 16) / lines);
            float fontSize = Math.min(9, leading * 9 / LINE_HEIGHT);
            float x = MARGIN;
            for (int i = 0; i < cells.size(); i++) {
                var column = definition.columns().get(i);
                float cellWidth = columnWidth(column);
                float baseline = y - fontSize;
                for (String line : cells.get(i)) {
                    text(line, x + 6, baseline, cellWidth - 12, fontSize, false, column.alignment(), INK);
                    baseline -= leading;
                }
                x += cellWidth;
            }
            y -= lines * leading;
            pageHasRows = true;
        }
        if (definition.rows().isEmpty()) {
            text(definition.emptyMessage(), MARGIN + 6, y - 10, usable - 12, 9, false, Alignment.LEFT, MUTED);
            y -= 15;
        }
        rule(y - 3);
        text(definition.total(), MARGIN + 6, y - 15, usable - 12, 9, true, Alignment.LEFT, INK);

        for (int i = 0; i < pages.size(); i++) {
            page = pages.get(i);
            rule(MARGIN + 12);
            text("TraceOne Network", MARGIN, MARGIN, usable, 8, false, Alignment.LEFT, MUTED);
            text("Página " + (i + 1) + " de " + pages.size(), MARGIN, MARGIN, usable, 8, false, Alignment.RIGHT, MUTED);
        }
    }

    private void newPage() {
        page = new StringBuilder("1 g 0 0 ").append(width).append(' ').append(height).append(" re f\n");
        pages.add(page);
        y = height - MARGIN;
        float logoWidth = 100;
        float logoHeight = logoWidth * Logo.IMAGE.getHeight() / Logo.IMAGE.getWidth();
        page.append("q ").append(logoWidth).append(" 0 0 ").append(logoHeight).append(' ')
            .append(MARGIN).append(' ').append(y - logoHeight).append(" cm /Logo Do Q\n");
        text("Emissão: " + date(issuance.instant(), issuance.zone()), MARGIN, y - 9, usable, 9, false, Alignment.RIGHT, MUTED);
        int nameLine = 0;
        for (String line : wrap("Usuário: " + issuance.user(), usable - logoWidth - 15, 9)) {
            text(line, MARGIN + logoWidth + 15, y - 21 - nameLine * LINE_HEIGHT,
                usable - logoWidth - 15, 9, false, Alignment.RIGHT, MUTED);
            nameLine++;
        }
        y -= Math.max(logoHeight, 12 + nameLine * LINE_HEIGHT) + 9;
        rule(y);
        y -= 21;
        if (pages.size() == 1) {
            text(definition.title(), MARGIN, y, usable, 16, true, Alignment.CENTER, INK);
            y -= 15;
            text(definition.subtitle(), MARGIN, y, usable, 10, false, Alignment.CENTER, MUTED);
            y -= 12;
            if (!definition.indicators().isEmpty()) {
                indicators();
                y -= 14;
            }
        }
        text(definition.section(), MARGIN, y, usable, 10, true, Alignment.LEFT, INK);
        y -= 8;
        rule(y);
        float x = MARGIN;
        for (var column : definition.columns()) {
            float cellWidth = columnWidth(column);
            text(column.label(), x + 6, y - 12, cellWidth - 12, 9, true, column.alignment(), INK);
            x += cellWidth;
        }
        y -= 16;
        rule(y);
        y -= 3;
    }

    private void indicators() {
        float gap = 6;
        float boxWidth = (usable - gap * (definition.indicators().size() - 1)) / definition.indicators().size();
        float x = MARGIN;
        for (var indicator : definition.indicators()) {
            page.append("0.94 0.95 0.97 rg ").append(x).append(' ').append(y - 40).append(' ')
                .append(boxWidth).append(" 40 re f\n");
            text(indicator.label(), x, y - 13, boxWidth, 9, false, Alignment.CENTER, MUTED);
            text(indicator.value(), x, y - 32, boxWidth, 18, true, Alignment.CENTER, INK);
            x += boxWidth + gap;
        }
        y -= 40;
    }

    private float columnWidth(Column column) {
        return usable * column.percent() / 100;
    }

    private void rule(float baseline) {
        page.append(RULE).append(" RG 0.55 w ").append(MARGIN).append(' ').append(baseline)
            .append(" m ").append(width - MARGIN).append(' ').append(baseline).append(" l S\n");
    }

    private void text(String value, float left, float baseline, float boxWidth, float size, boolean bold,
                      Alignment alignment, String color) {
        value = printable(value);
        float space = boxWidth - measure(value, size, bold);
        float x = left + switch (alignment) {
            case LEFT -> 0;
            case CENTER -> space / 2;
            case RIGHT -> space;
        };
        page.append(color).append(" rg BT /").append(bold ? "F2 " : "F1 ").append(size).append(" Tf ")
            .append(x).append(' ').append(baseline).append(" Td (")
            .append(value.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")).append(") Tj ET\n");
    }

    private static String printable(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
    }

    private static float measure(String value, float size, boolean bold) {
        String base = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return base.codePoints().map(c -> switch (c) {
            case '–' -> 556;
            case '—', '…', '‰' -> 1000;
            default -> c >= 32 && c <= 126 ? WIDTHS[c - 32] : 1000;
        }).sum() * size * (bold ? 1.06f : 1f) / 1000;
    }

    private static List<String> wrap(String input, float width, float size) {
        String value = printable(input);
        var lines = new ArrayList<String>();
        BreakIterator breaks = BreakIterator.getLineInstance(Locale.forLanguageTag("pt-BR"));
        breaks.setText(value);
        int start = 0;
        int fit = 0;
        for (int end = breaks.next(); end != BreakIterator.DONE; end = breaks.next()) {
            if (measure(value.substring(start, end), size, false) <= width) {
                fit = end;
                continue;
            }
            if (fit > start) {
                lines.add(value.substring(start, fit).stripTrailing());
                start = fit;
            }
            while (measure(value.substring(start, end), size, false) > width) {
                int split = start;
                int next = value.offsetByCodePoints(split, 1);
                while (next <= end && measure(value.substring(start, next), size, false) <= width) {
                    split = next;
                    if (split == end) break;
                    next = value.offsetByCodePoints(split, 1);
                }
                if (split == start) split = next;
                lines.add(value.substring(start, split));
                start = split;
            }
            fit = end;
        }
        if (start < value.length() || lines.isEmpty()) lines.add(value.substring(start).stripTrailing());
        return lines;
    }

    private byte[] serialize() {
        var objects = new ArrayList<byte[]>();
        objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));
        var children = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) children.append(6 + i * 2).append(" 0 R ");
        objects.add(bytes("<< /Type /Pages /Kids [" + children + "] /Count " + pages.size() + " >>"));
        objects.add(font("Helvetica"));
        objects.add(font("Helvetica-Bold"));
        objects.add(Logo.OBJECT);
        for (var content : pages) {
            int streamId = objects.size() + 2;
            objects.add(bytes("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + width + " " + height + "] "
                + "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> /XObject << /Logo 5 0 R >> >> "
                + "/Contents " + streamId + " 0 R >>"));
            objects.add(stream("", bytes(content.toString())));
        }

        var output = new ByteArrayOutputStream();
        output.writeBytes(bytes("%PDF-1.4\n%âãÏÓ\n"));
        var offsets = new ArrayList<Integer>();
        for (byte[] object : objects) {
            offsets.add(output.size());
            output.writeBytes(bytes(offsets.size() + " 0 obj\n"));
            output.writeBytes(object);
            output.writeBytes(bytes("\nendobj\n"));
        }
        int xref = output.size();
        var trailer = new StringBuilder("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int offset : offsets) trailer.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        trailer.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\n")
            .append("startxref\n").append(xref).append("\n%%EOF\n");
        output.writeBytes(bytes(trailer.toString()));
        return output.toByteArray();
    }

    private static byte[] font(String name) {
        return bytes("<< /Type /Font /Subtype /Type1 /BaseFont /" + name + " /Encoding /WinAnsiEncoding >>");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(ENCODING);
    }

    private static byte[] stream(String properties, byte[] content) {
        var result = new ByteArrayOutputStream();
        result.writeBytes(bytes("<< " + properties + " /Length " + content.length + " >>\nstream\n"));
        result.writeBytes(content);
        result.writeBytes(bytes("\nendstream"));
        return result.toByteArray();
    }

    private static final class Logo {
        private static final BufferedImage IMAGE = load();
        private static final byte[] OBJECT = encode();

        private static BufferedImage load() {
            try (var source = ReportPdf.class.getResourceAsStream("/reports/traceone-brand.png")) {
                var logo = ImageIO.read(source);
                var onWhite = new BufferedImage(logo.getWidth(), logo.getHeight(), BufferedImage.TYPE_INT_RGB);
                var graphics = onWhite.createGraphics();
                try {
                    graphics.drawImage(logo, 0, 0, Color.WHITE, null);
                } finally {
                    graphics.dispose();
                }
                return onWhite;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        private static byte[] encode() {
            var bytes = new ByteArrayOutputStream();
            try (var compressed = new DeflaterOutputStream(bytes)) {
                for (int y = 0; y < IMAGE.getHeight(); y++) {
                    for (int x = 0; x < IMAGE.getWidth(); x++) {
                        int color = IMAGE.getRGB(x, y);
                        compressed.write(color >> 16);
                        compressed.write(color >> 8);
                        compressed.write(color);
                    }
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
            return stream("/Type /XObject /Subtype /Image /ColorSpace /DeviceRGB /BitsPerComponent 8 "
                + "/Filter /FlateDecode /Width " + IMAGE.getWidth() + " /Height " + IMAGE.getHeight(), bytes.toByteArray());
        }
    }
}
