package com.dacon.core.savings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class SpecialConditionParser {
  private static final Pattern RATE = Pattern.compile("(?:연\\s*)?(\\d+(?:\\.\\d+)?)\\s*%p");

  public List<SavingsCatalogSnapshot.Condition> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    List<SavingsCatalogSnapshot.Condition> result = new ArrayList<>();
    for (String fragment : raw.split("[\\n\\r]+|(?<=\\.)\\s+")) {
      Matcher matcher = RATE.matcher(fragment);
      while (matcher.find()) {
        String label = fragment.trim().replaceAll("\\s+", " ");
        if (!label.isEmpty()) {
          result.add(
              new SavingsCatalogSnapshot.Condition(
                  label, new BigDecimal(matcher.group(1)), fragment.trim()));
        }
      }
    }
    return result;
  }
}
