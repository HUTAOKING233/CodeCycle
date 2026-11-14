package me.codecycle.common.web;

/** Synthetic Java input for CodeCycle integration testing. */
final class ReviewRuleFixture {
    int parseOrDefault(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
        }
        return 0;
    }
}
