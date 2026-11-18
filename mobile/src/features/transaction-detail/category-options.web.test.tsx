import { fireEvent, render, screen } from '@testing-library/react-native';

import { CategoryOptions } from './category-options.web';

jest.mock('@/global.css', () => ({}));
jest.mock('@/hooks/use-theme', () => ({
  useTheme: () => ({ textPrimary: '#0B1C3F' }),
}));

const choices = [
  { id: 'groceries', label: 'Food › Groceries' },
  { id: 'dining', label: 'Food › Dining out' },
];

// Each choice is a native radio wrapped in its <label>, which gives the radio its name.
function radioFor(label: string) {
  const wrapper = screen.getByText(label).parent;
  const input = wrapper?.children.find(
    (child) => typeof child !== 'string' && child.type === 'input',
  );
  if (!input || typeof input === 'string') throw new Error(`No radio for ${label}`);
  expect(wrapper?.type).toBe('label');
  return input;
}

describe('CategoryOptions (web, native radios)', () => {
  it('renders a labelled group of one named radio set with the current category checked', async () => {
    await render(
      <CategoryOptions
        label="Choose a category"
        choices={choices}
        selected="groceries"
        disabled={false}
        onSelect={jest.fn()}
      />,
    );
    // RNTL does not treat a plain View as accessible, so query the group by its label.
    expect(screen.getByLabelText('Choose a category')).toHaveProp(
      'accessibilityRole',
      'radiogroup',
    );
    const groceries = radioFor('Food › Groceries');
    const dining = radioFor('Food › Dining out');
    expect(groceries.props).toEqual(expect.objectContaining({ type: 'radio', checked: true }));
    expect(dining.props).toEqual(expect.objectContaining({ type: 'radio', checked: false }));
    // One shared name gives the browser's single Tab stop and arrow-key selection.
    expect(groceries.props.name).toBeTruthy();
    expect(dining.props.name).toBe(groceries.props.name);
  });

  it('selects a category when its radio changes', async () => {
    const onSelect = jest.fn();
    await render(
      <CategoryOptions
        label="Choose a category"
        choices={choices}
        selected="groceries"
        disabled={false}
        onSelect={onSelect}
      />,
    );
    await fireEvent(radioFor('Food › Dining out'), 'change');
    expect(onSelect).toHaveBeenCalledWith('dining');
  });

  it('disables every radio while a save is running', async () => {
    await render(
      <CategoryOptions
        label="Choose a category"
        choices={choices}
        selected={null}
        disabled
        onSelect={jest.fn()}
      />,
    );
    for (const choice of choices) {
      expect(radioFor(choice.label).props).toEqual(
        expect.objectContaining({ disabled: true, checked: false }),
      );
    }
  });
});
