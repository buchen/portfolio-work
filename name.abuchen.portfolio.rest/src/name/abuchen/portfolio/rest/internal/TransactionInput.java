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
import name.abuchen.portfolio.model.AccountTransferEntry;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
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

    TransactionsHandler.Event parse(Client client, JsonObject body, String clientName)
    {
        var type = text(body.get("type"), "type", true);
        var event = switch (type == null ? "" : type)
        {
            case "buy", "sell", "delivery-inbound", "delivery-outbound" -> investment(client, body, clientName, type);
            case "cash-transfer" -> cashTransfer(client, body, clientName);
            case "security-transfer" -> securityTransfer(client, body, clientName);
            default -> cash(client, body, clientName, type);
        };
        if (!errors.isEmpty())
            throw ApiException.validation(errors);
        return event;
    }

    private TransactionsHandler.Event cash(Client client, JsonObject body, String clientName, String type)
    {
        unknown(body, Set.of("type", "dateTime", "cashAccount", "instrument", "shares", "amount", "exDate",
                        "note", "source", "units"), "");
        var transaction = new AccountTransaction();
        transaction.setType(type == null ? null : CASH_TYPES.get(type));
        if (type != null && transaction.getType() == null)
            error("type", "invalid-value", "unsupported transaction type");
        transaction.setExDate(date(body.get("exDate"), "exDate", false));
        var account = reference(body.get("cashAccount"), "cashAccount", client.getAccounts(), Account::getUUID, true);
        transaction.setSecurity(reference(body.get("instrument"), "instrument", client.getSecurities(), Security::getUUID, false));
        common(body, transaction, clientName, false);
        statuses(TransactionRules.entryProfile().validate(transaction, account));
        return new TransactionsHandler.Event(account, transaction);
    }

    private TransactionsHandler.Event investment(Client client, JsonObject body, String clientName, String type)
    {
        boolean linked = type.equals("buy") || type.equals("sell");
        unknown(body, linked
                        ? Set.of("type", "dateTime", "investmentAccount", "cashAccount", "instrument", "shares", "amount", "note", "source", "units")
                        : Set.of("type", "dateTime", "investmentAccount", "instrument", "shares", "amount", "note", "source", "units"), "");
        var portfolio = reference(body.get("investmentAccount"), "investmentAccount", client.getPortfolios(), Portfolio::getUUID, true);
        var entry = linked ? new BuySellEntry() : null;
        var transaction = linked ? entry.getPortfolioTransaction() : new PortfolioTransaction();
        transaction.setType(switch (type)
        {
            case "buy" -> PortfolioTransaction.Type.BUY;
            case "sell" -> PortfolioTransaction.Type.SELL;
            case "delivery-inbound" -> PortfolioTransaction.Type.DELIVERY_INBOUND;
            default -> PortfolioTransaction.Type.DELIVERY_OUTBOUND;
        });
        transaction.setSecurity(reference(body.get("instrument"), "instrument", client.getSecurities(), Security::getUUID, true));
        common(body, transaction, clientName, true);
        if (linked)
        {
            var account = reference(body.get("cashAccount"), "cashAccount", client.getAccounts(), Account::getUUID, true);
            entry.setPortfolio(portfolio);
            entry.setAccount(account);
            entry.setType(transaction.getType());
            entry.setDate(transaction.getDateTime());
            entry.setSecurity(transaction.getSecurity());
            entry.setAmount(transaction.getAmount());
            entry.setCurrencyCode(transaction.getCurrencyCode());
            entry.setNote(transaction.getNote());
            entry.setSource(transaction.getSource());
            statuses(TransactionRules.entryProfile().validate(entry, account, portfolio));
        }
        else
            statuses(TransactionRules.entryProfile().validate(transaction, portfolio));
        return new TransactionsHandler.Event(portfolio, transaction);
    }

    private TransactionsHandler.Event cashTransfer(Client client, JsonObject body, String clientName)
    {
        unknown(body, Set.of("type", "dateTime", "fromCashAccount", "toCashAccount", "amount", "targetAmount",
                        "note", "source", "units"), "");
        var source = reference(body.get("fromCashAccount"), "fromCashAccount", client.getAccounts(), Account::getUUID, true);
        var target = reference(body.get("toCashAccount"), "toCashAccount", client.getAccounts(), Account::getUUID, true);
        var entry = new AccountTransferEntry(source, target);
        var transaction = entry.getSourceTransaction();
        common(body, transaction, clientName, false);
        entry.setDate(transaction.getDateTime());
        entry.setNote(transaction.getNote());
        entry.setSource(transaction.getSource());
        var amount = money(body.get("targetAmount"), "targetAmount", true);
        if (amount != null)
            entry.getTargetTransaction().setMonetaryAmount(amount);
        statuses(TransactionRules.entryProfile().validate(entry, source, target));
        return new TransactionsHandler.Event(source, transaction);
    }

    private TransactionsHandler.Event securityTransfer(Client client, JsonObject body, String clientName)
    {
        unknown(body, Set.of("type", "dateTime", "fromInvestmentAccount", "toInvestmentAccount", "instrument",
                        "shares", "amount", "note", "source", "units"), "");
        var source = reference(body.get("fromInvestmentAccount"), "fromInvestmentAccount", client.getPortfolios(), Portfolio::getUUID, true);
        var target = reference(body.get("toInvestmentAccount"), "toInvestmentAccount", client.getPortfolios(), Portfolio::getUUID, true);
        var entry = new PortfolioTransferEntry(source, target);
        var transaction = entry.getSourceTransaction();
        common(body, transaction, clientName, true);
        entry.setSecurity(reference(body.get("instrument"), "instrument", client.getSecurities(), Security::getUUID, true));
        entry.setDate(transaction.getDateTime());
        entry.setNote(transaction.getNote());
        entry.setSource(transaction.getSource());
        entry.setShares(transaction.getShares());
        entry.setAmount(transaction.getAmount());
        entry.setCurrencyCode(transaction.getCurrencyCode());
        statuses(TransactionRules.entryProfile().validate(entry, source, target));
        return new TransactionsHandler.Event(source, transaction);
    }

    private void common(JsonObject body, Transaction transaction, String clientName, boolean sharesRequired)
    {
        transaction.setDateTime(date(body.get("dateTime"), "dateTime", true));
        transaction.setNote(text(body.get("note"), "note", false));
        transaction.setSource(body.has("source") ? text(body.get("source"), "source", false) : clientName);
        var amount = money(body.get("amount"), "amount", true);
        if (amount != null)
            transaction.setMonetaryAmount(amount);
        if (sharesRequired || body.has("shares"))
        {
            var shares = scaled(body.get("shares"), "shares", 8);
            if (shares != null)
                transaction.setShares(shares);
        }
        units(body.get("units"), transaction);
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
