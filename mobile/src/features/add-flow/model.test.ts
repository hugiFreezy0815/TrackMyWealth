import { ApiError } from '@/api/client';

import {
  catalogueKey,
  defaultPensionScheme,
  formatIban,
  formatIdentifierInput,
  groupInFours,
  hasProblemCode,
  identifierErrorKey,
  identifierKind,
  identifierLabelKey,
  identifierOnBlur,
  identifierToSend,
  importFormatKey,
  institutionTypeKey,
  isNotFound,
  maskCardNumber,
  needsIdentifier,
  needsPrincipal,
  preferredCurrency,
  startingStep,
  validAccountName,
  validPortfolioName,
  validationErrors,
} from './model';
import { createdAccount, problem } from './test-fixtures';

describe('S5/S6 first-run selectors (#491)', () => {
  it('prefills CH/DE currency without calculating any financial amounts', () => {
    expect(preferredCurrency('CH', 'USD')).toBe('CHF');
    expect(preferredCurrency('DE', 'USD')).toBe('EUR');
    expect(preferredCurrency('AT', 'USD')).toBe('USD');
    expect(preferredCurrency(null, 'USD')).toBe('USD');
  });

  it('requires a portfolio name only without a catalogue entry, at most 100 characters', () => {
    expect(validPortfolioName('', true)).toBe(false);
    expect(validPortfolioName(' ', true)).toBe(false);
    expect(validPortfolioName('', false)).toBe(true);
    expect(validPortfolioName('x'.repeat(100), true)).toBe(true);
    expect(validPortfolioName('x'.repeat(101), false)).toBe(false);
    expect(validAccountName(' My account ')).toBe(true);
    expect(validAccountName('  ')).toBe(false);
  });

  it('uses a different catalogue key when the search or country changes', () => {
    expect(catalogueKey(' post ', null)).toEqual(['catalogue', 'post', '']);
    expect(catalogueKey('post', 'DE')).toEqual(['catalogue', 'post', 'DE']);
    expect(catalogueKey('DKB', 'DE')).not.toEqual(catalogueKey('post', 'DE'));
  });

  it('uses returned capability flags, not the declared type, for step two', () => {
    expect(startingStep(createdAccount({ accountType: 'SECURITIES', hasTransactions: true }))).toBe(
      'OPENING_BALANCE',
    );
    expect(
      startingStep(createdAccount({ accountType: 'CUSTOM_ASSET', manualValuation: true })),
    ).toBe('VALUATION');
    expect(startingStep(createdAccount({ accountType: 'CASH', hasAmortisation: true }))).toBe(
      'NONE',
    );
    expect(startingStep(createdAccount({ accountType: 'LOAN', hasTransactions: false }))).toBe(
      'NONE',
    );
  });

  it('exposes principal and identifier fields only where applicable', () => {
    expect(needsPrincipal('MORTGAGE')).toBe(true);
    expect(needsPrincipal('LOAN')).toBe(true);
    expect(needsPrincipal('CASH')).toBe(false);
    expect(needsIdentifier('CASH')).toBe(true);
    expect(needsIdentifier('CUSTOM_ASSET')).toBe(false);
    expect(identifierKind('CREDIT_CARD')).toBe('MASKED_CARD');
    expect(identifierKind('SAVINGS')).toBe('IBAN');
    expect(identifierLabelKey('MASKED_CARD')).toBe('addAccount.identifier.card');
    expect(identifierLabelKey('CONTRACT_NUMBER')).toBe('addAccount.identifier.contract');
    expect(identifierLabelKey('IBAN')).toBe('addAccount.identifier.iban');
  });

  it('preselects pillar 3a only for a CH portfolio and assumes no scheme elsewhere', () => {
    expect(defaultPensionScheme('CH')).toBe('CH_PILLAR_3A');
    expect(defaultPensionScheme('DE')).toBeNull();
    expect(defaultPensionScheme('AT')).toBeNull();
    expect(defaultPensionScheme(null)).toBeNull();
  });

  it('labels open enums through the #452 fallback', () => {
    expect(institutionTypeKey('BROKER')).toBe('institutionType.BROKER');
    expect(institutionTypeKey('NEOBANK')).toBe('institutionType.OTHER');
    expect(importFormatKey('SPREADSHEET')).toBe('importFormat.SPREADSHEET');
    expect(importFormatKey('OFX')).toBe('importFormat.OTHER');
  });
});

describe('account identifiers (#491)', () => {
  it('masks a fully typed card number on the device, keeping first 6 and last 4', () => {
    expect(maskCardNumber('4242 4242 4242 4242')).toBe('424242••••••4242');
    expect(maskCardNumber('4242-4242-4242-4')).toBe('424242•••2424');
    expect(maskCardNumber('4111111111111')).toBe('411111•••1111');
  });

  it('keeps an already masked number in any accepted mask character', () => {
    expect(maskCardNumber('4242 42XX XXXX 4242')).toBe('424242••••••4242');
    expect(maskCardNumber('**** **** **** 4242')).toBe('••••••••••••4242');
    expect(maskCardNumber('4242 42## #### 4242')).toBe('424242••••••4242');
  });

  it('rejects numbers that would expose more digits or are not card-shaped', () => {
    expect(maskCardNumber('4242 4242 42XX 4242')).toBeNull(); // 8 leading digits
    expect(maskCardNumber('4242')).toBeNull();
    expect(maskCardNumber('42424242')).toBeNull(); // too short for a full number
    expect(maskCardNumber('4242 4242 4242 4242 4242')).toBeNull(); // 20 digits
    expect(maskCardNumber('abcd')).toBeNull();
  });

  it('never sends an unmasked card number', () => {
    expect(identifierToSend('4242 4242 4242 4242', 'MASKED_CARD')).toBe('424242••••••4242');
    expect(identifierToSend('4242 4242', 'MASKED_CARD')).toBeNull();
    expect(identifierToSend('  ', 'MASKED_CARD')).toBeNull();
  });

  it('sends an IBAN compact and upper case, and a contract number as typed', () => {
    expect(identifierToSend('de89 3704 0044 0532 0130 00', 'IBAN')).toBe('DE89370400440532013000');
    expect(identifierToSend(' 12-345-678-90 ', 'CONTRACT_NUMBER')).toBe('12-345-678-90');
  });

  it('groups an IBAN while typing and masks a card number when the field is left', () => {
    expect(formatIban('de8937040044')).toBe('DE89 3704 0044');
    expect(formatIdentifierInput('ch93 0076 2011', 'IBAN')).toBe('CH93 0076 2011');
    expect(formatIdentifierInput('4242424242424242', 'MASKED_CARD')).toBe('4242424242424242');
    expect(identifierOnBlur('4242424242424242', 'MASKED_CARD')).toBe('4242 42•• •••• 4242');
    expect(identifierOnBlur('4242', 'MASKED_CARD')).toBe('4242');
    expect(identifierOnBlur('DE89', 'IBAN')).toBe('DE89');
    expect(groupInFours('12345678')).toBe('1234 5678');
  });
});

describe('server problems (#491)', () => {
  it('maps rejected fields to the generic field message, never the server wording', () => {
    const invalid = problem(400, 'VALIDATION_FAILED', {
      errors: [
        { field: 'name', message: 'raw detail' },
        { field: 'pensionScheme', message: 'raw' },
      ],
    });
    expect(validationErrors(invalid)).toEqual({
      name: 'addFlow.fieldError',
      pensionScheme: 'addFlow.fieldError',
    });
    expect(
      validationErrors(problem(422, 'ACCOUNT_IDENTIFIER_INVALID', { field: 'identifier' })),
    ).toEqual({ identifier: 'addFlow.fieldError' });
    expect(validationErrors(new ApiError('conflict', 409))).toEqual({});
    expect(validationErrors(new ApiError('down', 503))).toEqual({});
    expect(validationErrors(new Error('offline'))).toEqual({});
  });

  it('tells identifier failures apart by their problem code', () => {
    expect(identifierErrorKey(problem(422, 'ACCOUNT_IDENTIFIER_INVALID'), 'IBAN')).toBe(
      'addAccount.identifier.invalid.IBAN',
    );
    expect(identifierErrorKey(problem(422, 'ACCOUNT_IDENTIFIER_INVALID'), 'MASKED_CARD')).toBe(
      'addAccount.identifier.invalid.MASKED_CARD',
    );
    expect(identifierErrorKey(problem(409, 'ACCOUNT_IDENTIFIER_IN_USE'), 'IBAN')).toBe(
      'addAccount.identifier.inUse',
    );
    expect(identifierErrorKey(problem(409, 'VERSION_CONFLICT'), 'IBAN')).toBe(
      'addAccount.identifier.conflict',
    );
    expect(identifierErrorKey(problem(500, 'INTERNAL'), 'IBAN')).toBeNull();
  });

  it('recognises a removed portfolio by code or status', () => {
    expect(isNotFound(problem(404, 'NOT_FOUND'))).toBe(true);
    expect(isNotFound(new ApiError('gone', 404))).toBe(true);
    expect(isNotFound(problem(409, 'CONFLICT'))).toBe(false);
    expect(hasProblemCode(new Error('x'), 'NOT_FOUND')).toBe(false);
  });
});
