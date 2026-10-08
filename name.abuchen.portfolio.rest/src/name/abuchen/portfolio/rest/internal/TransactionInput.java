package name.abuchen.portfolio.rest.internal;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.datatransfer.ImportAction.Status;
import name.abuchen.portfolio.datatransfer.TransactionRules;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.Money;

/** Parses wire values before asking the shared entry rules about domain validity. */
@SuppressWarnings("nls")
final class TransactionInput
{
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
                    .withResolverStyle(ResolverStyle.STRICT);
    private static final Map<String, AccountTransaction.Type> CASH_TYPES = Map.of(
                    "deposit", AccountTransaction.Type.DEPOSIT, "removal", AccountTransaction.Type.REMOVAL,
                    "interest", AccountTransaction.Type.INTEREST, "interest-charge", AccountTransaction.Type.INTEREST_CHARGE,
                    "fee", AccountTransaction.Type.FEES, "fee-refund", AccountTransaction.Type.FEES_REFUND,
                    "tax", AccountTransaction.Type.TAXES, "tax-refund", AccountTransaction.Type.TAX_REFUND,
                    "dividend", AccountTransaction.Type.DIVIDENDS);
    private final List<ApiException.FieldError> errors = new ArrayList<>();

    record Cash(Account account, AccountTransaction transaction)
    {}

    Cash cash(Client client, JsonObject body, String clientName)
    {
        unknown(body, Set.of("type", "dateTime", "cashAccount", "instrument", "shares", "amount", "exDate",
                        "note", "source", "units"), "");
        var transaction = new AccountTransaction();
        var type = text(body.get("type"), "type", true);
        transaction.setType(type == null ? null : CASH_TYPES.get(type));
        if (type != null && transaction.getType() == null)
            error("type", "invalid-value", "unsupported transaction type");
        transaction.setDateTime(date(body.get("dateTime"), "dateTime", true));
        transaction.setExDate(date(body.get("exDate"), "exDate", false));
        transaction.setNote(text(body.get("note"), "note", false));
        transaction.setSource(body.has("source") ? text(body.get("source"), "source", false) : clientName);
        var account = reference(body.get("cashAccount"), "cashAccount", client.getAccounts(), Account::getUUID, true);
        transaction.setSecurity(reference(body.get("instrument"), "instrument", client.getSecurities(), Security::getUUID, false));
        var amount = money(body.get("amount"), "amount", true);
        if (amount != null)
            transaction.setMonetaryAmount(amount);
        if (body.has("shares"))
        {
            var shares = scaled(body.get("shares"), "shares", 8);
            if (shares != null)
                transaction.setShares(shares);
        }
        units(body.get("units"), transaction);
        statuses(TransactionRules.entryProfile().validate(transaction, account));
        if (!errors.isEmpty())
            throw ApiException.validation(errors);
        return new Cash(account, transaction);
    }

    private void units(JsonElement value, Transaction transaction)
    {
        if (value == null)
            return;
        if (!value.isJsonArray())
        {
            error("units", "invalid-type", "units must be an array");
            return;
        }
        int index = 0;
        for (var element : value.getAsJsonArray())
        {
            var field = "units[" + index++ + "]";
            var unit = object(element, field, true);
            if (unit == null)
                continue;
            unknown(unit, Set.of("type", "amount", "forex", "exchangeRate"), field + ".");
            var name = text(unit.get("type"), field + ".type", true);
            var type = name == null ? null : switch (name)
            {
                case "gross-value" -> Transaction.Unit.Type.GROSS_VALUE;
                case "fee" -> Transaction.Unit.Type.FEE;
                case "tax" -> Transaction.Unit.Type.TAX;
                default -> null;
            };
            if (name != null && type == null)
                error(field + ".type", "invalid-value", "unknown unit type");
            var amount = money(unit.get("amount"), field + ".amount", true);
            var forex = money(unit.get("forex"), field + ".forex", false);
            var rate = unit.has("exchangeRate") ? decimal(unit.get("exchangeRate"), field + ".exchangeRate") : null;
            if (rate != null && (!Double.isFinite(rate.doubleValue()) || rate.doubleValue() == 0))
            {
                error(field + ".exchangeRate", "out-of-range", "exchange rate is outside the model range");
                rate = null;
            }
            var violations = TransactionRules.entryProfile().validateUnit(type, amount, forex, rate,
                            transaction.getCurrencyCode(), field);
            statuses(violations);
            if (violations.isEmpty())
                transaction.addUnit(forex == null ? new Transaction.Unit(type, amount)
                                : new Transaction.Unit(type, amount, forex, rate));
        }
    }

    private Money money(JsonElement value, String field, boolean required)
    {
        var object = object(value, field, required);
        if (object == null)
            return null;
        unknown(object, Set.of("value", "currency"), field + ".");
        var currency = text(object.get("currency"), field + ".currency", true);
        var amount = scaled(object.get("value"), field + ".value", 2);
        if (currency != null && currency.isEmpty())
        {
            error(field + ".currency", "unsupported-currency", "a currency code is required");
            return null;
        }
        return currency == null || amount == null ? null : Money.of(currency, amount);
    }

    private Long scaled(JsonElement value, String field, int scale)
    {
        var number = decimal(value, field);
        if (number == null)
            return null;
        if (number.compareTo(BigDecimal.valueOf(Long.MIN_VALUE, scale)) < 0
                        || number.compareTo(BigDecimal.valueOf(Long.MAX_VALUE, scale)) > 0)
        {
            error(field, "out-of-range", "value is outside the model range");
            return null;
        }
        if (number.signum() == 0)
            return 0L;
        if (number.stripTrailingZeros().scale() > scale)
        {
            error(field, "too-many-decimals", "at most " + scale + " decimal places are supported");
            return null;
        }
        return number.movePointRight(scale).longValueExact();
    }

    private BigDecimal decimal(JsonElement value, String field)
    {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
        {
            error(field, value == null ? "required" : "invalid-type", "a number is required");
            return null;
        }
        try
        {
            return value.getAsBigDecimal();
        }
        catch (NumberFormatException e)
        {
            error(field, "out-of-range", "number is outside the supported range");
            return null;
        }
    }

    private <T> T reference(JsonElement value, String field, List<T> entities, Function<T, String> id, boolean required)
    {
        var object = object(value, field, required);
        if (object == null)
            return null;
        unknown(object, Set.of("uuid"), field + ".");
        var uuid = text(object.get("uuid"), field + ".uuid", true);
        if (uuid == null)
            return null;
        var entity = entities.stream().filter(item -> uuid.equals(id.apply(item))).findFirst().orElse(null);
        if (entity == null)
            error(field, "unknown-entity", "unknown " + field + " uuid");
        return entity;
    }

    private LocalDateTime date(JsonElement value, String field, boolean required)
    {
        var text = text(value, field, required);
        if (text == null)
            return null;
        try
        {
            if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"))
                throw new DateTimeParseException("invalid local date-time", text, 0);
            return LocalDateTime.parse(text, DATE_TIME);
        }
        catch (DateTimeParseException e)
        {
            error(field, "invalid-date", "expected local YYYY-MM-DDTHH:MM:SS without offset");
            return null;
        }
    }

    private JsonObject object(JsonElement value, String field, boolean required)
    {
        if (value == null)
        {
            if (required)
                error(field, "required", "an object is required");
            return null;
        }
        if (value.isJsonObject())
            return value.getAsJsonObject();
        error(field, "invalid-type", "expected an object");
        return null;
    }

    private String text(JsonElement value, String field, boolean required)
    {
        if (value == null || value.isJsonNull())
        {
            if (required)
                error(field, "required", "a string is required");
            return null;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())
            return value.getAsString();
        error(field, "invalid-type", "expected a string");
        return null;
    }

    private void unknown(JsonObject body, Set<String> fields, String prefix)
    {
        body.keySet().stream().filter(field -> !fields.contains(field))
                        .forEach(field -> error(prefix + field, "unknown-field", "field is not writable"));
    }

    private void statuses(List<Status> statuses)
    {
        statuses.forEach(status -> error(status.getField(), status.getRuleCode(),
                        "transaction violates " + status.getRuleCode()));
    }

    private void error(String field, String code, String message)
    {
        if (errors.stream().noneMatch(error -> error.field().equals(field) && error.code().equals(code)))
            errors.add(new ApiException.FieldError(field, code, message));
    }
}
