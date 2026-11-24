import type { Position } from './model';

/** The gain a position row shows, with the currency it is in. */
export type ShownGain = {
  amount: NonNullable<Position['unrealisedGain']>;
  percent: Position['unrealisedGainPercent'];
  currency: string;
};

/**
 * The gain a row shows (#527): in the depot currency when the position's cost there is known - it
 * matches the depot total and includes the FX change since purchase -, else in the cost basis' own
 * currency (a USD cost basis in a CHF depot: "+25.00 USD"). Null when there is neither.
 *
 * The server's account-currency gain wins, as it always did on S2/S3: it can rest on
 * `costBasisInAccountCurrency` alone. A cost-basis-currency gain needs the cost basis itself, so
 * a response that sends one without it is not trusted (contract: null without a cost basis).
 */
export function shownGain(position: Position, currency: string): ShownGain | null {
  if (position.unrealisedGainInAccountCurrency != null) {
    return {
      amount: position.unrealisedGainInAccountCurrency,
      percent: position.unrealisedGainInAccountCurrencyPercent ?? null,
      currency,
    };
  }
  if (position.costBasis == null) return null;
  if (position.unrealisedGain != null && position.unrealisedGainCurrency) {
    return {
      amount: position.unrealisedGain,
      percent: position.unrealisedGainPercent ?? null,
      currency: position.unrealisedGainCurrency,
    };
  }
  return null;
}
