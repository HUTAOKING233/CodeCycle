package me.codecycle.common.web;

/** Synthetic Java input for CodeCycle integration testing. */
final class ReviewRuleFixture {
    long parseLongOrDefault(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
        }
        return 0;
    }
}
