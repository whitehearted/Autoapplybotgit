package com.Job.applybot.bot;

import com.Job.applybot.Service.AnswerEngine;
import org.openqa.selenium.*;
import java.util.List;

public class PopupHandler {

    public static void handle(WebDriver driver) {
        System.out.println("[PopupHandler] START");

        for (int i = 0; i < 15; i++) {
            try {
                // Find current question text in popup
                List<WebElement> questions = driver.findElements(By.xpath(
                        "//*[contains(@class,'question') or contains(@class,'title') or contains(@class,'label')]"
                ));
                
                String q = "";
                for (WebElement qEl : questions) {
                    if (qEl.isDisplayed()) {
                        q = qEl.getText().trim();
                        if (!q.isEmpty()) break;
                    }
                }

                if (q.isEmpty()) {
                    System.out.println("[PopupHandler] No visible question text found. Exiting loop.");
                    break;
                }

                System.out.println("[PopupHandler] Question found: [" + q + "]");
                String ans = AnswerEngine.getAnswer(q.toLowerCase());
                System.out.println("[PopupHandler] Resolved answer: [" + ans + "]");

                boolean inputFilled = false;

                // 1. Check for visible text inputs or textareas in the popup layer
                List<WebElement> textInputs = driver.findElements(By.cssSelector(
                        "input[type='text'], textarea, [contenteditable='true']"
                ));

                for (WebElement input : textInputs) {
                    if (input.isDisplayed() && input.isEnabled()) {
                        System.out.println("[PopupHandler] Found text input box. Routing to AutocompleteHandler...");
                        inputFilled = AutocompleteHandler.fillAutocompleteField(driver, input, ans);
                        if (inputFilled) break;
                    }
                }

                // 2. If no text input was found or filled, try to find matching labels (checkbox/radio clicks)
                if (!inputFilled) {
                    List<WebElement> labels = driver.findElements(By.xpath(
                            "//label[contains(translate(., 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'), '" + ans.toLowerCase() + "')]"
                    ));

                    boolean clicked = false;
                    for (WebElement label : labels) {
                        if (label.isDisplayed()) {
                            try {
                                label.click();
                            } catch (Exception e) {
                                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", label);
                            }
                            System.out.println("[PopupHandler] Clicked label match: [" + label.getText() + "]");
                            clicked = true;
                            break;
                        }
                    }

                    // Fallback to first visible label if no exact text match matches
                    if (!clicked) {
                        List<WebElement> allLabels = driver.findElements(By.tagName("label"));
                        for (WebElement label : allLabels) {
                            if (label.isDisplayed()) {
                                try {
                                    label.click();
                                } catch (Exception e) {
                                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", label);
                                }
                                System.out.println("[PopupHandler] Fallback: Clicked first label: [" + label.getText() + "]");
                                break;
                            }
                        }
                    }
                }

                // Click Next / Submit / Save button to advance
                List<WebElement> actionButtons = driver.findElements(By.xpath(
                        "//button[contains(.,'Next') or contains(.,'Submit') or contains(.,'Save') or contains(.,'Continue')]"
                ));

                boolean clickedBtn = false;
                for (WebElement btn : actionButtons) {
                    if (btn.isDisplayed() && btn.isEnabled()) {
                        try {
                            btn.click();
                        } catch (Exception e) {
                            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btn);
                        }
                        System.out.println("[PopupHandler] Clicked Action Button ✓");
                        clickedBtn = true;
                        break;
                    }
                }

                if (!clickedBtn) {
                    System.out.println("[PopupHandler] No action button found. Bot may auto-advance.");
                }

                Thread.sleep(2000);

            } catch (Exception e) {
                System.out.println("[PopupHandler] Completed/Interrupted: " + e.getMessage());
                break;
            }
        }
        System.out.println("[PopupHandler] END");
    }
}