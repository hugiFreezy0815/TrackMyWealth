import { linkKeyProps } from './link-key.web';

type KeyDown = (event: object) => void;
const press = (props: object, key: string, extra: object = {}) => {
  const preventDefault = jest.fn();
  (props as { onKeyDown: KeyDown }).onKeyDown({
    key,
    repeat: false,
    altKey: false,
    ctrlKey: false,
    metaKey: false,
    shiftKey: false,
    preventDefault,
    ...extra,
  });
  return preventDefault;
};

describe('linkKeyProps (web role="link" pressables)', () => {
  it('follows the link on Enter', () => {
    const onPress = jest.fn();
    expect(press(linkKeyProps(onPress), 'Enter')).toHaveBeenCalledTimes(1);
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('ignores other keys, held-down Enter and modified Enter', () => {
    const onPress = jest.fn();
    const props = linkKeyProps(onPress);
    expect(press(props, ' ')).not.toHaveBeenCalled();
    expect(press(props, 'Tab')).not.toHaveBeenCalled();
    expect(press(props, 'Enter', { repeat: true })).not.toHaveBeenCalled();
    for (const modifier of ['altKey', 'ctrlKey', 'metaKey', 'shiftKey']) {
      expect(press(props, 'Enter', { [modifier]: true })).not.toHaveBeenCalled();
    }
    expect(onPress).not.toHaveBeenCalled();
  });
});
