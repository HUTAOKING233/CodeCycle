package me.codecycle.common.web;

/** Synthetic Java input for CodeCycle integration testing. */
final class ReviewRuleFixture {
    byte parseByteOrDefault(String value) {
        try {
            return Byte.parseByte(value);
        } catch (NumberFormatException invalid) {
        }
        return 0;
    }
}
