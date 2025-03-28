package jasmine.jragon.progress.bar;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;
import me.tongfei.progressbar.ProgressBarStyleBuilder;

import java.time.temporal.ChronoUnit;
import java.util.Random;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ProgressBarGenerator {
    private static final Random R = new Random();

    private static final int BAR_PICKER_COUNT = 4;

    private static final String PARTICLE_PHYSICS = " ░▒█";
    private static final String HORIZONTAL_BLOCKS_1 = " ▖▌▛█";
    private static final String HORIZONTAL_BLOCKS_2 = " ▖▄▙█";
    private static final String HORIZONTAL_BLOCKS_3 = " ▘▀▜█";

    private static final String[] LEFT_BRACKET_CHARS  = {"│", "⦕", "⦓", "⟬", "❰ ", "❮ "};
    private static final String[] RIGHT_BRACKET_CHARS = {"│", "⦖", "⦔", "⟭", " ❱", " ❯"};

    private static final byte[] COLOR_CODES = {92, 93, 94, 96, 97};

    private static final int TOP_LEVEL_UPDATE_INTERVAL_RATE = 250;

    private static final int SUB_PROGRESS_MAX_VALUE = 3;
    private static final int SUB_PROGRESS_INTERVAL_RATE = 1000;

    public static ProgressBar generateProgressBar(int count, String title, String unitName,
                                                  int unitSize, ProgressBarStyle style) {
        var builder = new ProgressBarBuilder()
                .setTaskName(title)
                .setInitialMax(count)
                .setUpdateIntervalMillis(TOP_LEVEL_UPDATE_INTERVAL_RATE)
                .setStyle(style)
//                .clearDisplayOnFinish()
                .setUnit(
                        count > 1 ?
                                unitName :
                                unitName.substring(0, unitName.length() - 1),
                        unitSize
                );

        if (count > 4) {
            builder.setSpeedUnit(ChronoUnit.MINUTES)
                    .showSpeed()
                    .setEtaFunction(Eta.generateFullAverageEta());
        } else {
            builder.hideEta();
        }

        return builder.build();
    }

    public static ProgressBarStyle generateProgressBarStyle() {
        return switch (R.nextInt(BAR_PICKER_COUNT)) {
            case 0 -> ProgressBarStyle.COLORFUL_UNICODE_BLOCK;
            case 1 -> ProgressBarStyle.COLORFUL_UNICODE_BAR;
            default -> generateCustomProgressBarStyle();
        };
    }

    private static ProgressBarStyle generateCustomProgressBarStyle() {
        var barStyle = new String[]{
                PARTICLE_PHYSICS, HORIZONTAL_BLOCKS_1,
                HORIZONTAL_BLOCKS_2, HORIZONTAL_BLOCKS_3
        };
        var barIndex = R.nextInt(barStyle.length);
        var bracketIndex = R.nextInt(LEFT_BRACKET_CHARS.length);

        var colorIndex = R.nextInt(COLOR_CODES.length);

        var chosenBar = barStyle[barIndex];

        return new ProgressBarStyleBuilder()
                .rightBracket(RIGHT_BRACKET_CHARS[bracketIndex])
                .leftBracket(LEFT_BRACKET_CHARS[bracketIndex])
                .colorCode(COLOR_CODES[colorIndex])
                .block(chosenBar.charAt(chosenBar.length() - 1))
                .fractionSymbols(chosenBar)
                .build();
    }

    public static ProgressBar generateSubProgressBar(String threadName, ProgressBarStyle style) {
        return new ProgressBarBuilder()
                .setTaskName(threadName)
                .setInitialMax(SUB_PROGRESS_MAX_VALUE)
                .setUpdateIntervalMillis(SUB_PROGRESS_INTERVAL_RATE)
                .clearDisplayOnFinish()
                .setStyle(style)
                .hideEta()
                .build();
    }
}
