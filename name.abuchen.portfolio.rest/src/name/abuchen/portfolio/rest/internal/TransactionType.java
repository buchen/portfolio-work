package name.abuchen.portfolio.rest.internal;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;

import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Transaction;

/** The event vocabulary shared by reads, filters and writes. */
@SuppressWarnings("nls")
enum TransactionType
{
    BUY("buy", Family.TRADE, AccountTransaction.Type.BUY, PortfolioTransaction.Type.BUY),
    SELL("sell", Family.TRADE, AccountTransaction.Type.SELL, PortfolioTransaction.Type.SELL),
    DELIVERY_INBOUND("delivery-inbound", Family.DELIVERY, null, PortfolioTransaction.Type.DELIVERY_INBOUND),
    DELIVERY_OUTBOUND("delivery-outbound", Family.DELIVERY, null, PortfolioTransaction.Type.DELIVERY_OUTBOUND),
    CASH_TRANSFER("cash-transfer", Family.CASH_TRANSFER, AccountTransaction.Type.TRANSFER_OUT, null),
    SECURITY_TRANSFER("security-transfer", Family.SECURITY_TRANSFER, null, PortfolioTransaction.Type.TRANSFER_OUT),
    DEPOSIT("deposit", AccountTransaction.Type.DEPOSIT),
    REMOVAL("removal", AccountTransaction.Type.REMOVAL),
    INTEREST("interest", AccountTransaction.Type.INTEREST),
    INTEREST_CHARGE("interest-charge", AccountTransaction.Type.INTEREST_CHARGE),
    FEE("fee", AccountTransaction.Type.FEES),
    FEE_REFUND("fee-refund", AccountTransaction.Type.FEES_REFUND),
    TAX("tax", AccountTransaction.Type.TAXES),
    TAX_REFUND("tax-refund", AccountTransaction.Type.TAX_REFUND),
    DIVIDEND("dividend", AccountTransaction.Type.DIVIDENDS);

    enum Family
    {
        CASH, TRADE, DELIVERY, CASH_TRANSFER, SECURITY_TRANSFER;

        Set<String> fields()
        {
            return switch (this)
            {
                case TRADE -> Set.of("type", "dateTime", "investmentAccount", "cashAccount", "instrument", "shares", "amount", "note", "source", "units");
                case DELIVERY -> Set.of("type", "dateTime", "investmentAccount", "instrument", "shares", "amount", "note", "source", "units");
                case CASH_TRANSFER -> Set.of("type", "dateTime", "fromCashAccount", "toCashAccount", "amount", "targetAmount", "note", "source", "units");
                case SECURITY_TRANSFER -> Set.of("type", "dateTime", "fromInvestmentAccount", "toInvestmentAccount", "instrument", "shares", "amount", "note", "source", "units");
                case CASH -> Set.of("type", "dateTime", "cashAccount", "instrument", "shares", "amount", "exDate", "note", "source", "units");
            };
        }
    }

    final String wireName;
    final Family family;
    final AccountTransaction.Type accountType;
    final PortfolioTransaction.Type portfolioType;

    TransactionType(String wireName, AccountTransaction.Type accountType)
    {
        this(wireName, Family.CASH, accountType, null);
    }

    TransactionType(String wireName, Family family, AccountTransaction.Type accountType,
                    PortfolioTransaction.Type portfolioType)
    {
        this.wireName = wireName;
        this.family = family;
        this.accountType = accountType;
        this.portfolioType = portfolioType;
    }

    static TransactionType fromWire(String name)
    {
        return Arrays.stream(values()).filter(type -> type.wireName.equals(name)).findFirst().orElse(null);
    }

    static TransactionType of(Transaction transaction)
    {
        // Incoming orphan records retain the same event type as outgoing transfers.
        if (transaction instanceof PortfolioTransaction investment)
        {
            var modelType = investment.getType() == PortfolioTransaction.Type.TRANSFER_IN
                            ? PortfolioTransaction.Type.TRANSFER_OUT : Objects.requireNonNull(investment.getType());
            return Arrays.stream(values()).filter(type -> type.portfolioType == modelType).findFirst().orElseThrow();
        }
        var cash = (AccountTransaction) transaction;
        var modelType = cash.getType() == AccountTransaction.Type.TRANSFER_IN
                        ? AccountTransaction.Type.TRANSFER_OUT : Objects.requireNonNull(cash.getType());
        return Arrays.stream(values()).filter(type -> type.accountType == modelType).findFirst().orElseThrow();
    }
}
