package me.codecycle.common.web;

/** Synthetic Java input for CodeCycle integration testing. */
final class ReviewRuleFixture {
    short parseShortOrDefault(String value) {
        try {
            return Short.parseShort(value);
        } catch (NumberFormatException invalid) {
        }
        return 0;
    }
}
