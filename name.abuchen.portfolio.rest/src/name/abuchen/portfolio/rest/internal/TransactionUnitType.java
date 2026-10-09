package name.abuchen.portfolio.rest.internal;

import java.util.Map;

import name.abuchen.portfolio.model.Transaction.Unit;

@SuppressWarnings("nls")
final class TransactionUnitType
{
    private static final Map<Unit.Type, String> NAMES = Map.of(Unit.Type.GROSS_VALUE, "gross-value",
                    Unit.Type.FEE, "fee", Unit.Type.TAX, "tax");

    private TransactionUnitType()
    {
    }

    static String toWire(Unit.Type type)
    {
        return NAMES.get(type);
    }

    static Unit.Type fromWire(String name)
    {
        return NAMES.entrySet().stream().filter(entry -> entry.getValue().equals(name)).map(Map.Entry::getKey)
                        .findFirst().orElse(null);
    }
}
