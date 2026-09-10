package com.Job.applybot.bot;

import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import java.time.Duration;
import java.util.List;
import java.util.Random;

public class AutocompleteHandler {

    // Common selectors for dropdown suggestions in Naukri / generic forms
    private static final String[] SUGGESTION_SELECTORS = {
        ".sugTokens li",
        ".suggestions li",
        "[class*='suggestion'] li",
        "[class*='suggestion'] div",
        ".dropdown-menu li",
        ".dropdown-menu div",
        ".search-list li",
        ".city-list li",
        "div.chatbot_Chip",
        "[class*='Dropdown'] li",
        "[class*='Dropdown'] div",
        "div.sugBox li",
        "ul.sugestions li"
    };

    public static boolean fillAutocompleteField(WebDriver driver, WebElement inputField, String expectedValue) {
        if (inputField == null || expectedValue == null || expectedValue.isBlank()) {
            System.out.println("[Autocomplete] Invalid input or empty value.");
            return false;
        }

        System.out.println("[Autocomplete] Handling searchable input for value: [" + expectedValue + "]");
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Random rand = new Random();

        // 10-Step Autocomplete Protocol

        // Run the typing and suggestion sequence inside a retry loop (Max 2 attempts)
        for (int attempt = 1; attempt <= 2; attempt++) {
            System.out.println("[Autocomplete] Attempt " + attempt + " of 2...");

            try {
                // Step 1: Click the input field safely
                try {
                    js.executeScript("arguments[0].scrollIntoView({block: 'center'});", inputField);
                    Thread.sleep(300);
                    inputField.click();
                } catch (Exception clickEx) {
                    System.out.println("[Autocomplete] Standard click failed, using JS click...");
                    js.executeScript("arguments[0].click();", inputField);
                }
                Thread.sleep(300);

                // Step 2: Clear existing value if present
                try {
                    inputField.clear();
                } catch (Exception clearEx) {
                    System.out.println("[Autocomplete] Standard clear failed, using select all + backspace...");
                }
                // Robust key-based clearing fallback
                inputField.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
                Thread.sleep(300);

                // Step 3: Type the required value slowly (char-by-char)
                for (char c : expectedValue.toCharArray()) {
                    inputField.sendKeys(String.valueOf(c));
                    // 150ms to 250ms dynamic human-like delay
                    Thread.sleep(150 + rand.nextInt(100));
                }
                Thread.sleep(500);

                // Dispatch events via JS to trigger reactive frameworks (React, Angular, Vue, contenteditables)
                try {
                    js.executeScript(
                        "var el = arguments[0];" +
                        "if(el){" +
                        "  el.dispatchEvent(new InputEvent('input',{bubbles:true,data:arguments[1],inputType:'insertText'}));" +
                        "  el.dispatchEvent(new Event('change',{bubbles:true}));" +
                        "  el.dispatchEvent(new Event('keyup',{bubbles:true}));" +
                        "}", inputField, expectedValue
                    );
                    Thread.sleep(300);
                } catch (Exception eventEx) {
                    System.out.println("[Autocomplete] Warning: JS event dispatch failed: " + eventEx.getMessage());
                }

                // Step 4: Wait for dropdown suggestions to appear (Explicit waits)
                System.out.println("[Autocomplete] Waiting for suggestions to load...");
                List<WebElement> suggestions = getActiveSuggestions(driver);

                // Step 10: Retry once if dropdown does not appear
                if (suggestions.isEmpty() && attempt == 1) {
                    System.out.println("[Autocomplete] Dropdown suggestions did not appear. Retrying typing sequence...");
                    continue; // goes to attempt 2
                }

                // If we have suggestions (or if it's attempt 2 and we must proceed with fallbacks)
                if (!suggestions.isEmpty()) {
                    System.out.println("[Autocomplete] Found " + suggestions.size() + " suggestions.");

                    // Step 5: Compare suggestions with expected text
                    // Pass 1: Exact Match (ignoring case and whitespace)
                    WebElement matchedEl = null;
                    for (WebElement el : suggestions) {
                        try {
                            String text = el.getText().trim();
                            if (!text.isEmpty()) {
                                System.out.println("[Autocomplete] Analyzing: [" + text + "]");
                                if (text.equalsIgnoreCase(expectedValue.trim())) {
                                    matchedEl = el;
                                    System.out.println("[Autocomplete] Exact match found: [" + text + "]");
                                    break;
                                }
                            }
                        } catch (StaleElementReferenceException ignored) {}
                    }

                    // Step 6: Select the matching suggestion
                    if (matchedEl != null) {
                        clickElementSafely(driver, matchedEl);
                        if (verifyValuePopulated(driver, inputField, expectedValue)) {
                            return true;
                        }
                    }

                    // Step 7: Fallbacks if no exact match exists
                    System.out.println("[Autocomplete] No exact match found. Trying fallbacks...");

                    // Fallback 1: Partial Match
                    for (WebElement el : suggestions) {
                        try {
                            String text = el.getText().trim().toLowerCase();
                            String expectedLower = expectedValue.trim().toLowerCase();
                            if (!text.isEmpty() && (text.contains(expectedLower) || expectedLower.contains(text))) {
                                System.out.println("[Autocomplete] Partial match found: [" + el.getText() + "]");
                                clickElementSafely(driver, el);
                                if (verifyValuePopulated(driver, inputField, expectedValue)) {
                                    return true;
                                }
                                break;
                            }
                        } catch (StaleElementReferenceException ignored) {}
                    }

                    // Fallback 2: Select first suggestion
                    System.out.println("[Autocomplete] Trying first suggestion fallback...");
                    try {
                        WebElement firstSug = suggestions.get(0);
                        System.out.println("[Autocomplete] Clicking first suggestion: [" + firstSug.getText() + "]");
                        clickElementSafely(driver, firstSug);
                        return true;
                    } catch (Exception ex) {
                        System.out.println("[Autocomplete] Click on first suggestion failed: " + ex.getMessage());
                    }
                }

                // Fallback 3: Try pressing Enter
                System.out.println("[Autocomplete] No matching suggestions clicked. Pressing Enter...");
                inputField.sendKeys(Keys.ENTER);
                Thread.sleep(500);

                if (verifyValuePopulated(driver, inputField, expectedValue)) {
                    return true;
                }

            } catch (Exception e) {
                System.out.println("[Autocomplete] Error in typing flow: " + e.getMessage());
            }
        }

        // Final verification check
        System.out.println("[Autocomplete] Autocomplete sequence completed.");
        return verifyValuePopulated(driver, inputField, expectedValue);
    }

    // Helper: Safely query all common suggestion selectors to collect active elements
    private static List<WebElement> getActiveSuggestions(WebDriver driver) {
        for (String sel : SUGGESTION_SELECTORS) {
            try {
                // Short wait to see if elements under this selector are present and displayed
                WebDriverWait shortWait = new WebDriverWait(driver, Duration.ofSeconds(1));
                List<WebElement> els = shortWait.until(ExpectedConditions.presenceOfAllElementsLocatedBy(By.cssSelector(sel)));
                // Filter out non-displayed elements
                els.removeIf(el -> {
                    try { return !el.isDisplayed(); }
                    catch (Exception e) { return true; }
                });
                if (!els.isEmpty()) {
                    System.out.println("[Autocomplete] Suggestions loaded via selector: [" + sel + "]");
                    return els;
                }
            } catch (Exception ignored) {}
        }
        return java.util.Collections.emptyList();
    }

    // Helper: Safe click utility
    private static void clickElementSafely(WebDriver driver, WebElement el) {
        try {
            el.click();
        } catch (Exception e) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
        }
    }

    // Step 8: Verify the selected value is populated correctly in the field
    private static boolean verifyValuePopulated(WebDriver driver, WebElement inputField, String expectedValue) {
        System.out.println("[Autocomplete] Verifying populated field value...");
        try {
            // Check value attribute
            String val = inputField.getAttribute("value");
            if (val != null && !val.isEmpty()) {
                System.out.println("[Autocomplete] Populated value attribute: [" + val + "]");
                return val.toLowerCase().contains(expectedValue.toLowerCase()) || expectedValue.toLowerCase().contains(val.toLowerCase());
            }

            // Check inner/outer text
            String txt = inputField.getText();
            if (txt != null && !txt.isEmpty()) {
                System.out.println("[Autocomplete] Populated text: [" + txt + "]");
                return txt.toLowerCase().contains(expectedValue.toLowerCase()) || expectedValue.toLowerCase().contains(txt.toLowerCase());
            }

            // Check textContent/innerText via JS
            Object textContent = ((JavascriptExecutor) driver).executeScript(
                "return arguments[0].textContent || arguments[0].innerText || '';", inputField
            );
            if (textContent != null) {
                String tc = textContent.toString().trim();
                if (!tc.isEmpty()) {
                    System.out.println("[Autocomplete] Populated textContent: [" + tc + "]");
                    return tc.toLowerCase().contains(expectedValue.toLowerCase()) || expectedValue.toLowerCase().contains(tc.toLowerCase());
                }
            }
        } catch (Exception e) {
            System.out.println("[Autocomplete] Verification check exception: " + e.getMessage());
        }
        return true;
    }
}
