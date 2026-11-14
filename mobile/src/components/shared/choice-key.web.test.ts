import { choiceKeyProps } from './choice-key.web';

type KeyDown = (event: { key: string; preventDefault: () => void }) => void;
const press = (props: object, key: string) => {
  const preventDefault = jest.fn();
  (props as { onKeyDown: KeyDown }).onKeyDown({ key, preventDefault });
  return preventDefault;
};

describe('choiceKeyProps (web radio and checkbox chips)', () => {
  it('selects on Space without scrolling the page', () => {
    const onSelect = jest.fn();
    const preventDefault = press(choiceKeyProps(onSelect, false), ' ');
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(preventDefault).toHaveBeenCalledTimes(1);
  });

  it('leaves other keys to the browser and does nothing while disabled', () => {
    const onSelect = jest.fn();
    expect(press(choiceKeyProps(onSelect, false), 'Enter')).not.toHaveBeenCalled();
    expect(press(choiceKeyProps(onSelect, false), 'Tab')).not.toHaveBeenCalled();
    expect(press(choiceKeyProps(onSelect, true), ' ')).not.toHaveBeenCalled();
    expect(onSelect).not.toHaveBeenCalled();
  });
});
