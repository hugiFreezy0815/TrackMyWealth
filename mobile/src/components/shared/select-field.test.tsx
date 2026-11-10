import { fireEvent, screen } from '@testing-library/react-native';

import { renderWithIntl } from '@/i18n/test-utils';

import { ChoiceField } from './fields';
import { SelectField, filterOptions } from './select-field';

jest.mock('@/global.css', () => ({}));

const options = [
  { value: 'CHF', label: 'CHF · Swiss Franc' },
  { value: 'EUR', label: 'EUR · Euro' },
  { value: 'ZAR', label: 'ZAR · South African Rand' },
];

describe('SelectField (#491)', () => {
  it('filters by label or code, ignoring case and accents', () => {
    expect(filterOptions(options, ' euro ').map((o) => o.value)).toEqual(['EUR']);
    expect(filterOptions(options, 'zar').map((o) => o.value)).toEqual(['ZAR']);
    expect(filterOptions([{ value: 'ZH', label: 'Zürich' }], 'zur')).toHaveLength(1);
    expect(filterOptions(options, '')).toHaveLength(3);
  });

  it('shows the choice, opens a searchable radio list and closes on a pick', async () => {
    const onChange = jest.fn();
    await renderWithIntl(
      <SelectField label="Currency" value="CHF" options={options} onChange={onChange} />,
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Currency: CHF · Swiss Franc' }));
    expect(screen.getByRole('radio', { name: 'CHF · Swiss Franc' })).toBeChecked();
    await fireEvent.changeText(screen.getByLabelText('Search'), 'rand');
    expect(screen.queryByRole('radio', { name: 'EUR · Euro' })).toBeNull();
    await fireEvent.press(screen.getByRole('radio', { name: 'ZAR · South African Rand' }));
    expect(onChange).toHaveBeenCalledWith('ZAR');
    expect(screen.queryByLabelText('Search')).toBeNull();
  });

  it('says when nothing matches', async () => {
    await renderWithIntl(
      <SelectField label="Currency" value={null} options={options} onChange={jest.fn()} />,
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Currency: Choose' }));
    await fireEvent.changeText(screen.getByLabelText('Search'), 'xyz');
    expect(screen.getByText('No match for “xyz”.')).toBeTruthy();
  });

  it('does not open while disabled', async () => {
    await renderWithIntl(
      <SelectField label="Currency" value="EUR" options={options} onChange={jest.fn()} disabled />,
    );
    await fireEvent.press(screen.getByRole('button', { name: 'Currency: EUR · Euro' }));
    expect(screen.queryByLabelText('Search')).toBeNull();
  });
});

describe('ChoiceField (#491)', () => {
  it('announces an error under its chips', async () => {
    await renderWithIntl(
      <ChoiceField
        label="Scheme"
        value={null}
        options={[{ value: 'A', label: 'A' }]}
        onChange={jest.fn()}
        error="Please check this field."
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('Please check this field.');
    expect(screen.getByRole('radio', { name: 'A' })).not.toBeChecked();
  });
});
