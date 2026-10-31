package ru.repaircalc.admin;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Draft contract. Unknown is explicit and is never converted to false or zero. */
@Component
public final class AdminProductValidator {
  public static final Set<String> VALUE_FIELDS =
      Set.of(
          "category",
          "price",
          "currency",
          "priceDate",
          "unit",
          "saleUnit",
          "packageSize",
          "consumption",
          "consumptionUnit",
          "materialFamily",
          "kind",
          "wet",
          "highLoad",
          "unheated",
          "rollWidth",
          "rollLength",
          "rapport");
  private static final Set<String> TEXT_FIELDS =
      Set.of(
          "sourceNamespace",
          "externalId",
          "name",
          "sku",
          "description",
          "application",
          "limitations",
          "comment");
  private static final Set<String> NUMBERS =
      Set.of("packageSize", "consumption", "rollWidth", "rollLength", "rapport");
  private static final Set<String> BOOLEANS = Set.of("wet", "highLoad", "unheated");
  private static final Set<String> UNITS = Set.of("м²", "л", "кг", "м", "шт.", "полотен");
  private static final Set<String> SALE_UNITS =
      Set.of("м²", "л", "кг", "м", "шт.", "уп.", "рул.", "бан.", "меш.", "кан.", "бух.");
  private static final Set<String> KINDS =
      Set.of("covering", "paint", "primer", "wallpaper", "adhesive", "socket", "cable");

  public ObjectNode validate(JsonNode input, String actor) {
    var errors = new ArrayList<AdminException.Detail>();
    var allowed = new HashSet<>(TEXT_FIELDS);
    allowed.addAll(VALUE_FIELDS);
    allowed.addAll(Set.of("attributes", "sources"));
    unknown(input, allowed, "", errors);
    for (var field : TEXT_FIELDS) {
      var value = input.get(field);
      if (value != null && (!value.isString() || value.asText().length() > 20000))
        error(errors, field, "Ожидается строка длиной до 20000 символов.");
    }
    for (var field : List.of("sourceNamespace", "externalId", "name")) {
      var value = input.path(field);
      if (!value.isString()
          || value.asText().isBlank()
          || value.asText().length() > 500
          || !value.asText().equals(value.asText().strip())) {
        error(errors, field, "Обязательна непустая строка до 500 символов без пробелов по краям.");
      }
    }
    var snapshot = (ObjectNode) input.deepCopy();
    for (var field : VALUE_FIELDS) {
      var value = input.get(field);
      if (value == null) continue;
      if (!value.isObject()) {
        error(errors, field, "Ожидается объект состояния поля.");
        continue;
      }
      unknown(value, Set.of("state", "value", "source", "confirmedBy"), field + ".", errors);
      var state = value.path("state").asText();
      if (!Set.of("unset", "unknown", "confirmed").contains(state))
        error(errors, field + ".state", "Допустимы unset, unknown, confirmed.");
      if (value.has("source")
          && (!value.path("source").isString() || value.path("source").asText().length() > 4000))
        error(errors, field + ".source", "Ожидается строка до 4000 символов.");
      if (!"confirmed".equals(state)) {
        if (value.has("value") && !value.path("value").isNull())
          error(errors, field + ".value", "Неподтверждённое поле не должно содержать значение.");
        ((ObjectNode) snapshot.get(field)).remove("confirmedBy");
        continue;
      }
      var actual = value.get("value");
      if (actual == null || actual.isNull()) {
        error(errors, field + ".value", "Для confirmed требуется значение.");
        continue;
      }
      if (!value.path("source").isString() || value.path("source").asText().isBlank())
        error(errors, field + ".source", "Укажите источник подтверждения.");
      if (BOOLEANS.contains(field)) {
        if (!actual.isBoolean()) error(errors, field + ".value", "Ожидается true или false.");
      } else if (NUMBERS.contains(field) || field.equals("price")) {
        boolean validType = field.equals("price") ? actual.isString() : actual.isNumber();
        try {
          if (!validType
              || (field.equals("price") && !actual.asText().matches("[0-9]{1,17}(\\.[0-9]{1,2})?")))
            throw new NumberFormatException();
          var number = new BigDecimal(actual.asText());
          if (number.signum() < (field.equals("rapport") ? 0 : 1)
              || number.precision() > 30
              || Math.abs(number.scale()) > 16) throw new NumberFormatException();
        } catch (NumberFormatException exception) {
          error(
              errors,
              field + ".value",
              "Требуется положительное число; раппорт может быть нулём. Цена — десятичная строка до"
                  + " 2 знаков после точки.");
        }
      } else if (!actual.isString()
          || actual.asText().isBlank()
          || actual.asText().length() > 4000) {
        error(errors, field + ".value", "Ожидается непустая строка до 4000 символов.");
      } else {
        var text = actual.asText();
        if (field.equals("materialFamily") && !Set.of("tile", "laminate", "vinyl").contains(text))
          error(errors, field + ".value", "Неизвестное семейство материала.");
        if (field.equals("kind") && !KINDS.contains(text))
          error(errors, field + ".value", "Вид товара не поддерживается.");
        if (field.equals("currency") && !text.equals("RUB"))
          error(errors, field + ".value", "Поддерживается RUB.");
        if ((field.equals("unit") || field.equals("consumptionUnit")) && !UNITS.contains(text))
          error(errors, field + ".value", "Единица не поддерживается.");
        if (field.equals("saleUnit") && !SALE_UNITS.contains(text))
          error(errors, field + ".value", "Единица продажи не поддерживается.");
        if (field.equals("priceDate")) {
          try {
            LocalDate.parse(text);
          } catch (java.time.DateTimeException exception) {
            error(errors, field + ".value", "Ожидается дата YYYY-MM-DD.");
          }
        }
      }
      // Author comes from the authenticated session, never from the supplied JSON.
      ((ObjectNode) snapshot.get(field)).put("confirmedBy", actor);
    }
    array(input, "attributes", Set.of("name", "value", "unit"), Set.of("name", "value"), errors);
    array(input, "sources", Set.of("label", "url"), Set.of("label"), errors);
    if (!errors.isEmpty())
      throw new AdminException(422, "validation_failed", "Проверьте поля карточки.", errors);
    return snapshot;
  }

  private static void array(
      JsonNode input,
      String field,
      Set<String> allowed,
      Set<String> required,
      List<AdminException.Detail> errors) {
    var array = input.get(field);
    if (array == null) return;
    if (!array.isArray() || array.size() > 200) {
      error(errors, field, "Ожидается массив до 200 элементов.");
      return;
    }
    for (int i = 0; i < array.size(); i++) {
      var item = array.get(i);
      var path = field + "." + i + ".";
      if (!item.isObject()) {
        error(errors, field + "." + i, "Ожидается объект.");
        continue;
      }
      unknown(item, allowed, path, errors);
      for (var key : allowed) {
        var value = item.get(key);
        if ((required.contains(key)
                && (value == null || !value.isString() || value.asText().isBlank()))
            || (value != null && (!value.isString() || value.asText().length() > 4000)))
          error(errors, path + key, "Ожидается строка; обязательное поле не может быть пустым.");
      }
    }
  }

  private static void unknown(
      JsonNode input, Set<String> allowed, String prefix, List<AdminException.Detail> errors) {
    for (var key : input.propertyNames())
      if (!allowed.contains(key)) error(errors, prefix + key, "Неизвестное поле.");
  }

  private static void error(List<AdminException.Detail> errors, String field, String message) {
    errors.add(new AdminException.Detail(field, "invalid_field", message));
  }
}
