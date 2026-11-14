package top.catnies.sparrow.cdkey.dialog;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.flattener.ComponentFlattener;
import net.kyori.adventure.text.flattener.FlattenerListener;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;

final class HistoryRowLayout {
    private static final int SPACE_WIDTH = 4;
    // 1.21.11 默认字体从空格到 ~ 的前进宽度, 单位为 GUI 像素
    private static final int[] PRINTABLE_ASCII = {
            4, 2, 4, 6, 6, 6, 6, 2, 4, 4, 4, 6, 2, 6, 2, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 2, 2, 5, 6, 5, 6,
            7, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 4, 6,
            3, 6, 6, 6, 6, 6, 5, 6, 6, 2, 6, 5, 3, 6, 6, 6,
            6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6, 4, 2, 4, 7
    };

    private final int[] columnWidths;

    public HistoryRowLayout(int... columnWidths) {
        this.columnWidths = columnWidths.clone();
    }

    @NotNull
    public Row row() {
        return new Row();
    }

    private static String shortenText(String value, int budget) {
        if (width(value) <= budget) return value;
        int available = budget - width("...");
        if (available < 0) return ".".repeat(Math.max(0, budget / advance('.')));
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            available -= advance(codePoint);
            if (available < 0) break;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end) + "...";
    }

    public static int width(@NotNull Component value) {
        return width(PlainTextComponentSerializer.plainText().serialize(value));
    }

    public static int width(@NotNull String value) {
        int pixels = 0;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            pixels += advance(codePoint);
            i += Character.charCount(codePoint);
        }
        return pixels;
    }

    private static int advance(int codePoint) {
        if (codePoint >= ' ' && codePoint <= '~') return PRINTABLE_ASCII[codePoint - ' '];
        if (codePoint == '◆') return 6;
        if (codePoint == '›') return 4;
        if (codePoint == '·') return 2;
        if (codePoint == '—') return 9; // 默认字体 nonlatin_european 的字形宽 8 像素, 字距 1 像素
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN ? 9 : 6;
    }

    // 按片段裁剪文本, 保留继承的颜色和点击事件.
    private static Component fit(Component value, int budget) {
        if (width(value) <= budget) return value;
        TextComponent.Builder result = Component.text();
        ComponentFlattener.basic().flatten(value, new FlattenerListener() {
            private final Deque<Style> styles = new ArrayDeque<>();
            private int remaining = Math.max(0, budget - width("..."));
            private boolean ended;

            @Override
            public void pushStyle(@NotNull Style style) {
                this.styles.push(this.styles.isEmpty() ? style : style.merge(this.styles.peek(), Style.Merge.Strategy.IF_ABSENT_ON_TARGET));
            }

            @Override
            public void component(@NotNull String text) {
                if (this.ended) return;
                int end = 0;
                while (end < text.length()) {
                    int point = text.codePointAt(end);
                    int pixels = advance(point);
                    if (pixels > this.remaining) break;
                    this.remaining -= pixels;
                    end += Character.charCount(point);
                }
                result.append(Component.text(text.substring(0, end), this.styles.peek()));
                if (end < text.length()) {
                    result.append(Component.text("...", this.styles.peek()));
                    this.ended = true;
                }
            }

            @Override
            public void popStyle(@NotNull Style style) {
                this.styles.pop();
            }
        });
        return result.build();
    }

    public final class Row {
        private final TextComponent.Builder line = Component.text();
        private int column;
        private int targetWidth;
        private int usedWidth;

        private Row() {
        }

        @NotNull
        public Row cell(@NotNull String value, @NotNull Style style) {
            String label = shortenText(value.replace('\r', ' ').replace('\n', ' '), this.budget());
            return this.append(label, style);
        }

        @NotNull
        public Row cell(@NotNull Component value, @NotNull Style fallback) {
            Component label = fit(value.style(value.style().merge(fallback, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)), this.budget());
            return this.append(label, width(label));
        }

        @NotNull
        public Row centeredCell(@NotNull Component value, @NotNull Style fallback) {
            Component label = fit(value.style(value.style().merge(fallback, Style.Merge.Strategy.IF_ABSENT_ON_TARGET)), this.budget());
            return this.centeredCell(label);
        }

        @NotNull
        public Row centeredCell(@NotNull String value, @NotNull Style style) {
            String label = shortenText(value.replace('\r', ' ').replace('\n', ' '), this.budget());
            return this.centeredCell(Component.text(label, style));
        }

        @NotNull
        public Row centeredCell(@NotNull Component value) {
            int pixels = width(value);
            int columnWidth = HistoryRowLayout.this.columnWidths[this.column];
            this.padTo(this.targetWidth + (columnWidth - pixels) / 2);
            return this.append(value, pixels);
        }

        private int budget() {
            int[] widths = HistoryRowLayout.this.columnWidths;
            return widths[this.column] - (this.column < widths.length - 1 ? SPACE_WIDTH : 0);
        }

        private Row append(String value, Style style) {
            return this.append(Component.text(value, style), width(value));
        }

        private Row append(Component value, int pixels) {
            this.line.append(value);
            this.targetWidth += HistoryRowLayout.this.columnWidths[this.column++];
            this.usedWidth += pixels;
            // 客户端逐行居中, 最后一列也按相同规则补齐
            this.padTo(this.targetWidth);
            return this;
        }

        private void padTo(int target) {
            int spaces = Math.max(0, (target - this.usedWidth + SPACE_WIDTH / 2) / SPACE_WIDTH);
            this.line.append(Component.text(" ".repeat(spaces)));
            this.usedWidth += spaces * SPACE_WIDTH;
        }

        @NotNull
        public Component build() {
            return this.line.build();
        }
    }
}